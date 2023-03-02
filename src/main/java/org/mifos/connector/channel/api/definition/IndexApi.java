package org.mifos.connector.channel.api.definition;
import org.springframework.web.bind.annotation.GetMapping;
import javax.servlet.http.HttpServletResponse;

public interface IndexApi {

    @GetMapping("/")
    String index(HttpServletResponse response) throws Exception;

}