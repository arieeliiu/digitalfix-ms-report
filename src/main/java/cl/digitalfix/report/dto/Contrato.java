package cl.digitalfix.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class Contrato {
    private Contrato() {}
    public record Sobre(@NotNull @Pattern(regexp="1\\.0") String schemaVersion,
                        @NotNull @Pattern(regexp="GENERAR_REPORTE") String type,
                        @NotNull UUID eventId, @NotNull Instant timestamp,
                        @NotNull UUID traceId,
                        @NotNull @Pattern(regexp="[1-9][0-9]{0,18}") String correlationId,
                        @NotNull @Valid Orden payload) {}

    public record Orden(@NotNull @Positive Long ordenId,
                        @NotNull @Pattern(regexp="CERRADA") String estado,
                        @NotBlank @Size(max=100) String solicitanteId,
                        @NotBlank @Size(max=100) String tecnicoId,
                        @NotNull @Positive Long servicioId,
                        @NotBlank @Size(max=1000) String descripcion,
                        @NotBlank @Size(max=300) String direccion,
                        @NotNull Instant fechaCreacion, @NotNull Instant fechaCierre,
                        @NotNull @Size(max=1000) List<@NotNull @Valid Repuesto> repuestos) {}

    public record Repuesto(@NotNull @Positive Long repuestoId, @NotNull @Positive Integer cantidad) {}
}
