package org.mifos.connector.channel.api;

import com.fasterxml.jackson.core.*;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.*;

public interface CollectionApi {

    @PostMapping("/channel/collection")
    String collection(@RequestHeader(value="Platform-TenantId") String tenant,
                      @RequestHeader(value="X-CorrelationID") String correlationId,
                      @RequestBody CollectionRequestDTO requestBody) throws ExecutionException, InterruptedException, JsonProcessingException;

}
