package com.rsreach.api.web.contacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.contacts.ContactExportService;
import com.rsreach.contacts.ContactImportModels;
import com.rsreach.contacts.ContactImportService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class ContactImportController {

    private final ContactImportService importService;
    private final ContactExportService exportService;
    private final CustomerAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public ContactImportController(
            ContactImportService importService,
            ContactExportService exportService,
            CustomerAuthorizationService authorizationService,
            ObjectMapper objectMapper
    ) {
        this.importService = importService;
        this.exportService = exportService;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/contacts/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContactImportModels.JobView preview(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "mapping", required = false) String mappingJson
    ) throws Exception {
        Context ctx = requireImport(principal);
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV file is required");
        }
        ContactImportModels.ColumnMapping mapping = parseMapping(mappingJson);
        return importService.preview(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                file.getOriginalFilename(),
                file.getInputStream(),
                mapping
        );
    }

    @PostMapping("/contacts/import/{jobId}/commit")
    public ContactImportModels.JobView commit(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID jobId,
            @RequestHeader(value = RsReachConstants.HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) CommitBody body
    ) {
        Context ctx = requireImport(principal);
        String policy = body == null ? null : body.conflictPolicy();
        return importService.commit(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), jobId, policy, idempotencyKey);
    }

    @GetMapping("/contacts/import/{jobId}")
    public ContactImportModels.JobView getJob(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID jobId
    ) {
        Context ctx = requireImport(principal);
        return importService.getJob(ctx.tenantId(), ctx.workspaceId(), jobId);
    }

    @GetMapping("/contacts/export")
    public ResponseEntity<?> export(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status
    ) {
        Context ctx = requireExport(principal);
        ContactImportModels.ExportResult result = exportService.export(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), query, status);
        if (result.async()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", result.jobId());
            body.put("async", true);
            body.put("status", "PENDING");
            body.put("rowCount", result.rowCount());
            body.put("filename", result.filename());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(result.csvContent());
    }

    @GetMapping("/contacts/export/jobs/{jobId}")
    public ResponseEntity<?> getExportJob(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        Context ctx = requireExport(principal);
        ContactImportModels.ExportJobStatus status =
                exportService.getExportJobStatus(ctx.tenantId(), ctx.workspaceId(), jobId);
        if (!status.ready() || !download) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", status.id());
            body.put("status", status.status());
            body.put("rowCount", status.rowCount());
            body.put("ready", status.ready());
            HttpStatus httpStatus = status.ready() ? HttpStatus.OK : HttpStatus.ACCEPTED;
            return ResponseEntity.status(httpStatus)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
        ContactImportModels.ExportResult result =
                exportService.getExportJob(ctx.tenantId(), ctx.workspaceId(), jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(result.csvContent());
    }

    private Context requireImport(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_IMPORT);
        return new Context(tenantId, workspaceId);
    }

    private Context requireExport(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_EXPORT);
        return new Context(tenantId, workspaceId);
    }

    private ContactImportModels.ColumnMapping parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return new ContactImportModels.ColumnMapping("display_name", "email", "phone", Map.of());
        }
        try {
            MappingPart part = objectMapper.readValue(mappingJson, MappingPart.class);
            return new ContactImportModels.ColumnMapping(
                    part.displayName(),
                    part.email(),
                    part.phone(),
                    part.customFields() == null ? Map.of() : part.customFields(),
                    part.consentEmailMarketing(),
                    part.consentWhatsappMarketing()
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid mapping JSON");
        }
    }

    private record Context(UUID tenantId, UUID workspaceId) {
    }

    public record MappingPart(
            String displayName,
            String email,
            String phone,
            Map<String, String> customFields,
            String consentEmailMarketing,
            String consentWhatsappMarketing
    ) {
    }

    public record CommitBody(String conflictPolicy) {
    }
}
