package cl.digitalfix.report.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import cl.digitalfix.report.dto.Contrato.Sobre;

@Service
public class InformePdf {
    private final Path fuente;
    public InformePdf(@Value("${report.font}") String fuente) { this.fuente = Path.of(fuente); }

    public void generarSiFalta(Path destino, Sobre sobre) throws IOException {
        if (Files.exists(destino)) {
            // Recuperación tras guardar PDF y fallar antes del registro de éxito.
            try (var existente = Loader.loadPDF(destino.toFile())) {
                if (!sobre.eventId().toString().equals(existente.getDocumentInformation().getCustomMetadataValue("eventId")))
                    throw new ErrorPermanente("pdf_no_corresponde_al_evento");
            }
            return;
        }
        try (var documento = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            documento.getDocumentInformation().setTitle("DigitalFix - Informe técnico de orden " + sobre.payload().ordenId());
            documento.getDocumentInformation().setCustomMetadataValue("eventId", sobre.eventId().toString());
            var font = PDType0Font.load(documento, fuente.toFile());
            try (var pagina = new Paginas(documento, font, sobre.payload().ordenId())) {
                var d = sobre.payload();
                pagina.seccion("ORDEN DE TRABAJO");
                pagina.texto("Orden: " + d.ordenId() + "   |   Estado final: " + d.estado());
                pagina.texto("Servicio solicitado (ID): " + d.servicioId());
                pagina.texto("Solicitante (ID): " + d.solicitanteId());
                pagina.texto("Técnico (ID): " + d.tecnicoId());
                pagina.texto("Dirección: " + d.direccion());
                pagina.texto("Fecha de creación (UTC): " + d.fechaCreacion());
                pagina.texto("Fecha de cierre (UTC): " + d.fechaCierre());
                pagina.seccion("DESCRIPCIÓN DEL TRABAJO");
                pagina.texto(d.descripcion());
                pagina.seccion("REPUESTOS UTILIZADOS");
                if (d.repuestos().isEmpty()) pagina.texto("Sin repuestos registrados.");
                for (var r : d.repuestos()) pagina.texto("Repuesto ID " + r.repuestoId() + "   |   Cantidad: " + r.cantidad());
                pagina.seccion("TRAZABILIDAD");
                pagina.texto("Generado (UTC): " + Instant.now());
                pagina.texto("Evento: " + sobre.eventId());
                pagina.texto("Trace ID: " + sobre.traceId());
                pagina.texto("Fecha del evento (UTC): " + sobre.timestamp());
                pagina.texto("Informe elaborado a partir de los datos recibidos de la orden cerrada.");
            }
            documento.save(bytes);
            RegistroPersistente.escribirAtomico(destino, bytes.toByteArray());
        }
    }

    private static final class Paginas implements AutoCloseable {
        private static final float MARGEN = 48, ANCHO = PDRectangle.A4.getWidth() - 96;
        private final PDDocument documento;
        private final PDType0Font fuente;
        private final Long orden;
        private PDPageContentStream contenido;
        private float y;

        Paginas(PDDocument documento, PDType0Font fuente, Long orden) throws IOException {
            this.documento = documento; this.fuente = fuente; this.orden = orden;
            nueva();
        }
        private void nueva() throws IOException {
            if (contenido != null) contenido.close();
            var pagina = new PDPage(PDRectangle.A4);
            documento.addPage(pagina);
            contenido = new PDPageContentStream(documento, pagina);
            contenido.setNonStrokingColor(0.08f, 0.23f, 0.34f);
            escribir("DigitalFix", MARGEN, 790, 23);
            escribir("INFORME TÉCNICO  /  MANTENIMIENTO ELÉCTRICO", MARGEN, 769, 9);
            contenido.setStrokingColor(0.12f, 0.65f, 0.60f);
            contenido.moveTo(MARGEN, 754); contenido.lineTo(MARGEN + ANCHO, 754); contenido.stroke();
            contenido.setNonStrokingColor(0.35f, 0.35f, 0.35f);
            escribir("DigitalFix  |  Orden " + orden + "  |  Página " + documento.getNumberOfPages(), MARGEN, 30, 9);
            contenido.setNonStrokingColor(0.12f, 0.12f, 0.12f);
            y = 730;
        }
        void seccion(String titulo) throws IOException {
            if (y < 115) nueva();
            y -= 12;
            contenido.setNonStrokingColor(0.08f, 0.32f, 0.40f);
            escribir(titulo, MARGEN, y, 11);
            contenido.setNonStrokingColor(0.12f, 0.12f, 0.12f);
            y -= 24;
        }
        void texto(String texto) throws IOException {
            // Corte por ancho real de glifos; también soporta palabras sin espacios.
            for (String parrafo : texto.replace('\r', '\n').split("\n", -1)) {
                StringBuilder linea = new StringBuilder();
                for (int cp : parrafo.codePoints().toArray()) {
                    String c = Character.isISOControl(cp) ? " " : new String(Character.toChars(cp));
                    try { fuente.encode(c); }
                    catch (IllegalArgumentException e) { throw new ErrorPermanente("caracter_sin_glifo_pdf"); }
                    if (fuente.getStringWidth(linea + c) * 10.5f / 1000 > ANCHO) {
                        int espacio = linea.lastIndexOf(" ");
                        if (espacio > 0) {
                            linea(linea.substring(0, espacio));
                            String resto = linea.substring(espacio + 1);
                            linea.setLength(0); linea.append(resto);
                        } else { linea(linea.toString()); linea.setLength(0); }
                    }
                    linea.append(c);
                }
                linea(linea.toString());
            }
            y -= 4;
        }
        private void linea(String texto) throws IOException {
            if (y < 60) nueva();
            escribir(texto, MARGEN, y, 10.5f); y -= 16;
        }
        private void escribir(String texto, float x, float y, float size) throws IOException {
            contenido.beginText(); contenido.setFont(fuente, size);
            contenido.newLineAtOffset(x, y); contenido.showText(texto); contenido.endText();
        }
        @Override public void close() throws IOException { if (contenido != null) contenido.close(); }
    }
}
