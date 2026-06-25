package com.agentmemory.web;

import com.agentmemory.core.UserId;
import com.agentmemory.security.UserRepository;
import com.agentmemory.security.UserService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-management endpoints for multi-user mode (issue #39): {@code add / list / expire / revive /
 * rotate-token}. The thin Go CLI ({@code agent-memory user ...}) drives these; the client never touches
 * Postgres directly. These are <strong>admin</strong> routes — {@link com.agentmemory.security.SecurityConfiguration}
 * gates them behind the root token, so only an operator (not a per-user token) can manage accounts.
 *
 * <p>A freshly issued token is returned <em>once</em> by {@code add}/{@code rotate-token} and never
 * again (only its hash is stored). {@link UserService} is injected via {@link ObjectProvider} so a
 * single-user / DB-less context still constructs the controller but answers {@code 503} (the bean is
 * absent unless multi-user mode is on).
 */
@RestController
public class UserController {

    private final ObjectProvider<UserService> users;

    public UserController(ObjectProvider<UserService> users) {
        this.users = users;
    }

    @PostMapping("/users/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody UserRequest req) {
        UserService svc = users.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            UserService.IssuedToken issued = svc.add(UserId.of(req.username()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", issued.username().value());
            body.put("token", issued.token()); // shown once; never retrievable again
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (UserService.UserExistsException e) {
            return conflict(e);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @GetMapping("/users/list")
    public ResponseEntity<Map<String, Object>> list() {
        UserService svc = users.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        List<Map<String, Object>> rows = svc.list().stream().map(UserController::userRow).toList();
        return ResponseEntity.ok(Map.of("users", rows));
    }

    @PostMapping("/users/expire")
    public ResponseEntity<Map<String, Object>> expire(@RequestBody UserRequest req) {
        return statusFlip(req, true);
    }

    @PostMapping("/users/revive")
    public ResponseEntity<Map<String, Object>> revive(@RequestBody UserRequest req) {
        return statusFlip(req, false);
    }

    @PostMapping("/users/rotate-token")
    public ResponseEntity<Map<String, Object>> rotateToken(@RequestBody UserRequest req) {
        UserService svc = users.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            return svc.rotateToken(UserId.of(req.username()))
                    .map(issued -> {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("username", issued.username().value());
                        body.put("token", issued.token());
                        return ResponseEntity.ok(body);
                    })
                    .orElseGet(() -> notFound(req.username()));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> statusFlip(UserRequest req, boolean expire) {
        UserService svc = users.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            UserId username = UserId.of(req.username());
            boolean changed = expire ? svc.expire(username) : svc.revive(username);
            if (!changed) {
                return notFound(req.username());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username.value());
            body.put("status", expire ? "expired" : "active");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    private static Map<String, Object> userRow(UserRepository.UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.username().value());
        m.put("status", u.status());
        m.put("createdAt", iso(u.createdAt()));
        m.put("rotatedAt", iso(u.rotatedAt()));
        m.put("expiresAt", iso(u.expiresAt()));
        return m;
    }

    private static String iso(Instant t) {
        return t == null ? null : t.toString();
    }

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable",
                        "reason", "user management requires multi-user mode (set agent-memory.auth.token-pepper)"));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String username) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("status", "not_found", "reason", "no such user: " + username));
    }

    private static ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "conflict", "reason", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", "error", "reason", String.valueOf(e.getMessage())));
    }

    /** Request body for the user ops: just a username. */
    public record UserRequest(String username) {
    }
}
