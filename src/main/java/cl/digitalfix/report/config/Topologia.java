package cl.digitalfix.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("report.rabbit")
public record Topologia(String direct, String topic, String dlx, String queue, String dlq,
                        String directKey, String topicKey, String deadKey) {}
