package cl.digitalfix.report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import cl.digitalfix.report.config.Topologia;

@SpringBootApplication
@EnableConfigurationProperties(Topologia.class)
public class ReportApplication {
    public static void main(String[] args) { SpringApplication.run(ReportApplication.class, args); }
}
