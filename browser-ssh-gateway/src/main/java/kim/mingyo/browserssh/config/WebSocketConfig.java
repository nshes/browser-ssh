package kim.mingyo.browserssh.config;

import kim.mingyo.browserssh.web.FileTransferWebSocketHandler;
import kim.mingyo.browserssh.web.TerminalWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final TerminalWebSocketHandler handler;
    private final FileTransferWebSocketHandler fileTransferHandler;
    private final AccessProperties accessProperties;

    public WebSocketConfig(
            TerminalWebSocketHandler handler,
            FileTransferWebSocketHandler fileTransferHandler,
            AccessProperties accessProperties
    ) {
        this.handler = handler;
        this.fileTransferHandler = fileTransferHandler;
        this.accessProperties = accessProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/terminal")
                .setAllowedOrigins(accessProperties.publicOrigin());
        registry.addHandler(fileTransferHandler, "/ws/files")
                .setAllowedOrigins(accessProperties.publicOrigin());
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(24 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        // Terminal idle and hard limits are enforced by TerminalWebSocketHandler.
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }
}
