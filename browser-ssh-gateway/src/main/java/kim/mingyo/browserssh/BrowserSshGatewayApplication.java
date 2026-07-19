package kim.mingyo.browserssh;

import kim.mingyo.browserssh.config.AccessProperties;
import kim.mingyo.browserssh.config.DisplayProperties;
import kim.mingyo.browserssh.config.FileTransferProperties;
import kim.mingyo.browserssh.config.TerminalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        AccessProperties.class,
        DisplayProperties.class,
        FileTransferProperties.class,
        TerminalProperties.class
})
public class BrowserSshGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(BrowserSshGatewayApplication.class, args);
    }
}
