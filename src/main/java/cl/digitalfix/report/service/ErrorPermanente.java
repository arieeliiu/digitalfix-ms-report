package cl.digitalfix.report.service;

public class ErrorPermanente extends RuntimeException {
    public ErrorPermanente(String mensaje) { super(mensaje); }
}
