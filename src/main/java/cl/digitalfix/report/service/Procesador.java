package cl.digitalfix.report.service;

import java.io.IOException;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import cl.digitalfix.report.dto.Contrato.Sobre;

@Service
public class Procesador {
    private static final Logger log = LoggerFactory.getLogger(Procesador.class);
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Validator validator;
    private final RegistroPersistente registro;
    private final InformePdf informes;
    private final Espera espera;

    public Procesador(Validator validator, RegistroPersistente registro, InformePdf informes, Espera espera) {
        this.validator = validator; this.registro = registro; this.informes = informes; this.espera = espera;
    }

    // Una instancia y un volumen bloqueado; ningún procesamiento concurrente por evento.
    public synchronized void procesar(byte[] cuerpo) throws IOException, InterruptedException {
        Sobre sobre = null;
        try {
            if (cuerpo.length > 262144) throw new ErrorPermanente("mensaje_mayor_256_KiB");
            sobre = json.readValue(cuerpo, Sobre.class);
            if (sobre == null || !validator.validate(sobre).isEmpty()) throw new ErrorPermanente("datos_invalidos");
            if (!sobre.correlationId().equals(sobre.payload().ordenId().toString()))
                throw new ErrorPermanente("correlacion_inconsistente");
            if (sobre.payload().fechaCierre().isBefore(sobre.payload().fechaCreacion()))
                throw new ErrorPermanente("cierre_anterior_a_creacion");
        } catch (RuntimeException e) {
            registrar(sobre, 0, e instanceof ErrorPermanente ? e.getMessage() : "json_invalido");
            throw new ErrorPermanente("contrato_invalido");
        }
        // Canonicalizar el DTO validado: espacios/orden de propiedades JSON no alteran identidad.
        String huella = RegistroPersistente.sha256(json.writeValueAsBytes(sobre));
        for (int intento = 1; intento <= 3; intento++) {
            try {
                if (registro.completado(sobre.eventId(), huella)) {
                    registrar(sobre, intento, "duplicado_completado"); return;
                }
                informes.generarSiFalta(registro.documento(sobre.eventId()), sobre);
                registro.completar(sobre.eventId());
                registrar(sobre, intento, "completado"); return;
            } catch (IOException e) {
                registrar(sobre, intento, "almacenamiento_" + e.getClass().getSimpleName());
                if (intento == 3) throw e;
                try { espera.pausar(intento == 1 ? 2000 : 5000); }
                catch (InterruptedException interrumpido) { Thread.currentThread().interrupt(); throw interrumpido; }
            } catch (RuntimeException e) {
                registrar(sobre, intento, e instanceof ErrorPermanente ? e.getMessage() : "error_no_recuperable");
                throw e;
            }
        }
    }

    private void registrar(Sobre s, int intento, String motivo) {
        String orden = s != null && s.correlationId() != null && s.correlationId().matches("[1-9][0-9]{0,18}")
                ? s.correlationId() : "desconocida";
        log.info("mensaje eventId={} traceId={} ordenId={} intento={} motivo={}",
                s == null ? "desconocido" : s.eventId(), s == null ? "desconocido" : s.traceId(), orden, intento, motivo);
    }
}
