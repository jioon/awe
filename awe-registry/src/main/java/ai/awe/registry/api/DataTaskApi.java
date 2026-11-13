package ai.awe.registry.api;

import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.consumption.DataTaskService;
import ai.awe.registry.support.JsonDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 只暴露受支持的数据能力，不接收 Agent 的完整工作流或任意可执行代码。 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public final class DataTaskApi {
    private final DataTaskService tasks;
    private final JsonDocuments json;

    public DataTaskApi(DataTaskService tasks, JsonDocuments json) {
        this.tasks = tasks;
        this.json = json;
    }

    @GetMapping("/capabilities")
    public String capabilities(HttpServletRequest request) {
        return json.write(tasks.capabilities(grant(request)));
    }

    @PostMapping("/data-tasks")
    public String create(@RequestBody String body, HttpServletRequest request) {
        if (body.getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw new IllegalArgumentException("Data capability request exceeds 64 KiB");
        return json.write(tasks.create(json.object(body), grant(request)));
    }

    @GetMapping("/data-tasks")
    public String list(HttpServletRequest request) {
        return json.write(tasks.list(grant(request)));
    }

    @GetMapping("/data-tasks/{id}")
    public String get(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(tasks.get(id, grant(request)));
    }

    private ConsumerAccess.Grant grant(HttpServletRequest request) {
        return (ConsumerAccess.Grant) request.getAttribute("awe.consumer");
    }
}
