package cl.digitalfix.report.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean
    Declarables recursosReport(Topologia t) {
        var direct = new DirectExchange(t.direct(), true, false);
        var topic = new TopicExchange(t.topic(), true, false);
        var dlx = new DirectExchange(t.dlx(), true, false);
        var cola = QueueBuilder.durable(t.queue()).withArgument("x-queue-type", "classic")
                .deadLetterExchange(t.dlx()).deadLetterRoutingKey(t.deadKey()).build();
        var dlq = QueueBuilder.durable(t.dlq()).withArgument("x-queue-type", "classic").build();
        return new Declarables(direct, topic, dlx, cola, dlq,
                BindingBuilder.bind(cola).to(direct).with(t.directKey()),
                BindingBuilder.bind(cola).to(topic).with(t.topicKey()),
                BindingBuilder.bind(dlq).to(dlx).with(t.deadKey()));
    }
}
