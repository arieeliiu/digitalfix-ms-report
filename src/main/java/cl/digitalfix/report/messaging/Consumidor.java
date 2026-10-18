package cl.digitalfix.report.messaging;

import java.io.IOException;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import cl.digitalfix.report.service.Procesador;

@Component
public class Consumidor {
    private final Procesador procesador;
    public Consumidor(Procesador procesador) { this.procesador = procesador; }

    @RabbitListener(queues="${report.rabbit.queue}")
    public void recibir(Message mensaje, Channel canal) throws IOException {
        long etiqueta = mensaje.getMessageProperties().getDeliveryTag();
        // Recibir Message crudo: un JSON inválido también pasa por el rechazo controlado.
        try { procesador.procesar(mensaje.getBody()); }
        catch (Exception e) { canal.basicNack(etiqueta, false, false); return; }
        // Fuera del catch: un fallo al confirmar no transforma un éxito en rechazo.
        canal.basicAck(etiqueta, false);
    }
}
