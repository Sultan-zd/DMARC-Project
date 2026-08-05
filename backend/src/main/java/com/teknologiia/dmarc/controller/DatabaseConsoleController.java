package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.database.TablePage;
import com.teknologiia.dmarc.dto.database.TableSummary;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import com.teknologiia.dmarc.security.PlatformAccess;
import com.teknologiia.dmarc.service.DatabaseConsoleService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Reading and changing the database directly.
 *
 * <p>Everything here is available to the operator through a database client
 * already. The difference is that a client listens on localhost and this does not,
 * so anything that destroys data asks for the operator's password again. A stolen
 * session is then worth reading, not erasing.
 */
@RestController
@RequestMapping("/api/platform/database")
@RequiredArgsConstructor
public class DatabaseConsoleController {

    private final DatabaseConsoleService database;
    private final PlatformAccess platformAccess;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** A destructive call carries the operator's password alongside its target. */
    public record Confirmation(@NotBlank String password) {}

    @GetMapping("/tables")
    public List<TableSummary> tables(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOperator(caller);
        return database.tables();
    }

    @GetMapping("/tables/{table}")
    public TablePage rows(@AuthenticationPrincipal AuthenticatedUser caller,
                          @PathVariable String table,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(name = "page_size", defaultValue = "25") int pageSize,
                          @RequestParam(required = false) String search,
                          @RequestParam(defaultValue = "false") boolean reveal) {
        requireOperator(caller);
        return database.rows(table, page, pageSize, search, reveal, caller.getUsername());
    }

    @PostMapping("/tables/{table}/rows/{key}/delete")
    public Map<String, String> deleteRow(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable String table,
                                         @PathVariable String key,
                                         @RequestBody Confirmation confirmation) {
        requireOperator(caller);
        requirePassword(caller, confirmation);
        database.deleteRow(table, key, caller.getUsername());
        return Map.of("detail", "Row deleted from " + table + ".");
    }

    @PostMapping("/tables/{table}/clear")
    public Map<String, Object> clear(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable String table,
                                     @RequestBody Confirmation confirmation) {
        requireOperator(caller);
        requirePassword(caller, confirmation);
        long removed = database.clearTable(table, caller.getUsername());
        return Map.of("detail", removed + " row(s) removed from " + table + ".", "removed", removed);
    }

    private void requireOperator(AuthenticatedUser caller) {
        if (caller == null || !platformAccess.isOperator(caller.getUsername())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * Re-checks the password held by the caller's own account.
     *
     * <p>A session token proves somebody signed in at some point, possibly on a
     * machine that has since been left unlocked. Destroying data should need the
     * one thing only the operator knows.
     */
    private void requirePassword(AuthenticatedUser caller, Confirmation confirmation) {
        var user = userRepository.findByUsername(caller.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (confirmation == null || confirmation.password() == null
                || !passwordEncoder.matches(confirmation.password(), user.getHashedPassword())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That password is not correct.");
        }
    }
}
