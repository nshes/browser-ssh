package kim.mingyo.browserssh.web;

import kim.mingyo.browserssh.config.DisplayProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gateway")
public class GatewayConfigController {
    private final DisplayProperties displayProperties;

    public GatewayConfigController(DisplayProperties displayProperties) {
        this.displayProperties = displayProperties;
    }

    @GetMapping("/config")
    public ResponseEntity<GatewayConfig> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new GatewayConfig(
                        displayProperties.targetName(),
                        displayProperties.uploadPath(),
                        displayProperties.downloadPath()
                ));
    }

    public record GatewayConfig(String targetName, String uploadPath, String downloadPath) {
    }
}
