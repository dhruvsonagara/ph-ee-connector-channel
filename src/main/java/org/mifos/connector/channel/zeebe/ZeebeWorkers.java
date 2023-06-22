package org.mifos.connector.channel.zeebe;

import static java.util.Comparator.naturalOrder;
import static org.mifos.connector.channel.camel.config.CamelProperties.BATCH_ID;
import static org.mifos.connector.channel.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.channel.zeebe.ZeebeVariables.ERROR_DESCRIPTION;
import static org.mifos.connector.channel.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.channel.zeebe.ZeebeVariables.SAMPLED_TX_IDS;
import static org.mifos.connector.channel.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.channel.zeebe.ZeebeVariables.TRANSACTION_VALID;
import static org.mifos.connector.channel.zeebe.ZeebeVariables.TRANSFER_CREATE_FAILED;
import static org.mifos.connector.common.mojaloop.type.ErrorCode.fromCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.camunda.zeebe.client.ZeebeClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.json.JSONObject;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ZeebeWorkers {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    public static final String TRANSFER_FAILED = "transferFailed";
    public static final String TRANSFER_STATE = "transferState";

    public static final String MESSAGE = "message";

    @PostConstruct
    public void setupWorkers() {
        workerSendErrorToChannel();
        workerSendSuccessToChannel();
        workerNotifyOperator();
        workerNotifyAmsFailure();
        workerSendUnknownToChannel();
        workerSendPayeeSuccessToChannel();
        workerSendPayeeFailureToChannel();
        workerInvokeAcknowledgementWorkflows();
        zeebeConsistencyWorker();
        validateTransactionalData();

    }

    private void workerSendErrorToChannel() {
        zeebeClient.newWorker().jobType("send-error-to-channel").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

            Object errorInfoVariable = job.getVariablesAsMap().get(ERROR_INFORMATION);
            if (errorInfoVariable != null) {
                JSONObject errorInformation = new JSONObject((String) errorInfoVariable).getJSONObject("errorInformation");
                int errorCode = Integer.parseInt(errorInformation.getString("errorCode"));
                logger.error("Error occurred with code: {} type: {} message: {}", errorCode, fromCode(errorCode).name(),
                        errorInformation.getString("errorDescription"));
            }

            Map<String, Object> existingVariables = job.getVariablesAsMap();

            existingVariables.put(TRANSFER_CREATE_FAILED, "false");

            client.newCompleteCommand(job.getKey()).variables(existingVariables).send();
        }).name("send-error-to-channel").maxJobsActive(workerMaxJobs).open();
    }

    private void workerSendSuccessToChannel() {
        zeebeClient.newWorker().jobType("send-success-to-channel").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            client.newCompleteCommand(job.getKey()).send();
        }).name("send-success-to-channel").maxJobsActive(workerMaxJobs).open();
    }

    private void workerNotifyOperator() {
        zeebeClient.newWorker().jobType("notify-operator").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            client.newCompleteCommand(job.getKey()).send();
        }).name("notify-operator").maxJobsActive(workerMaxJobs).open();
    }

    private void workerNotifyAmsFailure() {
        zeebeClient.newWorker().jobType("notify-ams-failure").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            client.newCompleteCommand(job.getKey()).send();
        }).name("notify-ams-failure").maxJobsActive(workerMaxJobs).open();
    }

    private void workerSendUnknownToChannel() {
        zeebeClient.newWorker().jobType("send-unknown-to-channel").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

            Map<String, Object> variables = job.getVariablesAsMap();
            logger.info("Transfer {} response did not arrive after {} retries, operator decision required! Variables:\n{}",
                    variables.get(TRANSACTION_ID), 3, // TODO externalize bpmn expression variables
                    variables.keySet().stream().sorted(naturalOrder()).map(k -> k + " -- " + variables.get(k))
                            .collect(Collectors.joining("\n")));

            client.newCompleteCommand(job.getKey()).send();
        }).name("send-unknown-to-channel").maxJobsActive(workerMaxJobs).open();
    }

    private void workerSendPayeeSuccessToChannel() {
        zeebeClient.newWorker().jobType("send-payee-success-to-channel").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            Map<String, Object> variables = job.getVariablesAsMap();

            variables.put(ERROR_INFORMATION, "Custom Error: Failed to deposit!");
            variables.put(TRANSFER_FAILED, true);

            zeebeClient.newPublishMessageCommand().messageName("transferResponse").correlationKey(variables.get("transactionId").toString())
                    .timeToLive(Duration.ofMillis(30000)).variables(variables).send().join();

            client.newCompleteCommand(job.getKey()).send().join();
        }).name("send-payee-success-to-channel").maxJobsActive(workerMaxJobs).open();
    }

    private void workerSendPayeeFailureToChannel() {
        zeebeClient.newWorker().jobType("send-payee-failure-to-channel").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            Map<String, Object> variables = job.getVariablesAsMap();

            variables.put(TRANSFER_STATE, "COMMITTED");
            variables.put(TRANSFER_FAILED, false);

            zeebeClient.newPublishMessageCommand().messageName("transferResponse").correlationKey(variables.get("transactionId").toString())
                    .timeToLive(Duration.ofMillis(30000)).variables(variables).send().join();

            client.newCompleteCommand(job.getKey()).send().join();
        }).name("send-payee-failure-to-channel").maxJobsActive(workerMaxJobs).open();
    }

    private void workerInvokeAcknowledgementWorkflows() {
        zeebeClient.newWorker().jobType("invoke-ack-workers").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            Map<String, Object> variables = job.getVariablesAsMap();

            String successfulTxIdsStr = variables.get(SAMPLED_TX_IDS).toString();
            ArrayList<String> successfulTxIds = new Gson().fromJson(successfulTxIdsStr, ArrayList.class);

            Exchange exchange = new DefaultExchange(camelContext);
            exchange.setProperty("successfulTxIds", successfulTxIds);
            exchange.setProperty("batchId", variables.get(BATCH_ID));
            producerTemplate.send("direct:invoke-ack-workers", exchange);

            client.newCompleteCommand(job.getKey()).send().join();
        }).name("send-payee-failure-to-channel").maxJobsActive(workerMaxJobs).open();
    }

    private void zeebeConsistencyWorker() {
        zeebeClient.newWorker().jobType("zeebe-consistency-worker").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            Map<String, Object> variables = job.getVariablesAsMap();
            variables.put(MESSAGE, "hello world");
            client.newCompleteCommand(job.getKey()).send().join();
        }).name("zeebe-consistency-worker").maxJobsActive(workerMaxJobs).open();
    }

    // validate transactional data
    private void validateTransactionalData() {
        zeebeClient.newWorker().jobType("validate-transactional-data").handler((client, job) -> {
            logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
            Map<String, Object> variables = job.getVariablesAsMap();
            ObjectMapper mapper = new ObjectMapper();
            TransactionChannelRequestDTO channelRequestDTO = mapper.readValue(variables.get(CHANNEL_REQUEST).toString(),
                    TransactionChannelRequestDTO.class);
            boolean valid = validateTxn(channelRequestDTO);
            if (!valid) {
                variables.put(TRANSACTION_VALID, false);
                variables.put(ERROR_INFORMATION, "Custom Error: Transaction Invalid");
                variables.put(ERROR_DESCRIPTION, "Transaction data was wrong or incomplete");
            } else {
                variables.put(TRANSACTION_VALID, true);
            }
            client.newCompleteCommand(job.getKey()).variables(variables).send().join();
        }).name("validate-transactional-data").maxJobsActive(workerMaxJobs).open();

    }

    private boolean validateTxn(TransactionChannelRequestDTO channelRequestDTO) {
        boolean valid = false;
        String payerIdentifierType = channelRequestDTO.getPayer().getPartyIdInfo().getPartyIdType().toString();
        String payeeIdentifierType = channelRequestDTO.getPayee().getPartyIdInfo().getPartyIdType().toString();
        if (payerIdentifierType.equalsIgnoreCase("MSISDN") || payerIdentifierType.equalsIgnoreCase("ACCOUNTID")) {
            String payerIdentity = channelRequestDTO.getPayer().getPartyIdInfo().getPartyIdentifier();
            valid = payerIdentity.matches("^[\\d*#+]+$");
            if (payeeIdentifierType.equalsIgnoreCase("MSISDN") || payeeIdentifierType.equalsIgnoreCase("ACCOUNTID")) {
                String payeeIdentity = channelRequestDTO.getPayee().getPartyIdInfo().getPartyIdentifier();
                valid = payeeIdentity.matches("^[\\d*#+]+$");
            }
        }
        if (channelRequestDTO.getNote().contains("Duplicate Transaction")) {
            valid = !channelRequestDTO.getNote().contains("Duplicate transaction");
        }

        return valid;
    }
}
