package com.marlabs.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * The {@link RestClient} used for every call to the Python service.
     *
     * <p>Two deliberate properties:
     *
     * <ul>
     *   <li><b>5 second timeout</b> on both connect and read. A read timeout
     *       alone would let a TCP-level stall hang the request indefinitely.</li>
     *   <li><b>Zero retries.</b> No retry interceptor, no Spring Retry, and
     *       {@link SimpleClientHttpRequestFactory} does not retry on its own. A
     *       failed call surfaces immediately as a failure. This matters for
     *       correctness as much as latency: these requests are not idempotent
     *       from an audit standpoint, and a silent second attempt would make a
     *       timeout indistinguishable from a slow success.</li>
     * </ul>
     */
    @Bean
    public RestClient policyServiceRestClient(PolicyServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private ClientHttpRequestFactory requestFactory(PolicyServiceProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return factory;
    }
}
