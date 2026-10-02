package com.example.pim.adapter.web;

import com.example.pim.adapter.web.WebDtos.DiffResponse;
import com.example.pim.adapter.web.WebDtos.PublicationResponse;
import com.example.pim.adapter.web.WebDtos.PublishRequest;
import com.example.pim.adapter.web.WebDtos.VersionResponse;
import com.example.pim.application.port.in.EditDraftUseCase;
import com.example.pim.application.port.in.EditDraftUseCase.PutVariationCommand;
import com.example.pim.application.port.in.EditDraftUseCase.RemoveVariationCommand;
import com.example.pim.application.port.in.EditDraftUseCase.ReplaceTemplateCommand;
import com.example.pim.application.port.in.ProductSetQueries;
import com.example.pim.application.port.in.PublishVersionUseCase;
import com.example.pim.application.port.in.PublishVersionUseCase.PublishVersionCommand;
import com.example.pim.application.port.in.ReviewVersionUseCase;
import com.example.pim.application.port.in.ReviewVersionUseCase.ApproveVersionCommand;
import com.example.pim.application.port.in.ReviewVersionUseCase.DiscardDraftCommand;
import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import static com.example.pim.adapter.web.ProductSetController.USER_HEADER;

/**
 * Draft edits and approval take the {@code lockVersion} from the caller's last read; a mismatch
 * returns 409 instead of silently overwriting someone else's change.
 */
@RestController
@RequestMapping("/versions/{versionId}")
class VersionController {

    private final EditDraftUseCase editDraft;
    private final ReviewVersionUseCase review;
    private final PublishVersionUseCase publish;
    private final ProductSetQueries queries;

    VersionController(EditDraftUseCase editDraft, ReviewVersionUseCase review,
                      PublishVersionUseCase publish, ProductSetQueries queries) {
        this.editDraft = editDraft;
        this.review = review;
        this.publish = publish;
        this.queries = queries;
    }

    @GetMapping
    VersionResponse get(@PathVariable UUID versionId) {
        return VersionResponse.from(queries.version(new VersionId(versionId)));
    }

    @PutMapping("/template")
    VersionResponse replaceTemplate(@PathVariable UUID versionId, @RequestParam int lockVersion,
                                    @RequestBody Map<String, Object> template) {
        return VersionResponse.from(editDraft.replaceTemplate(
                new ReplaceTemplateCommand(new VersionId(versionId), lockVersion, Attributes.of(template))));
    }

    @PutMapping("/variations/{key}")
    VersionResponse putVariation(@PathVariable UUID versionId, @PathVariable String key, @RequestParam int lockVersion,
                                 @RequestBody Map<String, Object> attributes) {
        return VersionResponse.from(editDraft.putVariation(new PutVariationCommand(
                new VersionId(versionId), lockVersion, new VariationKey(key), Attributes.of(attributes))));
    }

    @DeleteMapping("/variations/{key}")
    VersionResponse removeVariation(@PathVariable UUID versionId, @PathVariable String key, @RequestParam int lockVersion) {
        return VersionResponse.from(editDraft.removeVariation(
                new RemoveVariationCommand(new VersionId(versionId), lockVersion, new VariationKey(key))));
    }

    @PostMapping("/approve")
    VersionResponse approve(@PathVariable UUID versionId, @RequestParam int lockVersion,
                            @RequestHeader(USER_HEADER) String user) {
        return VersionResponse.from(review.approve(
                new ApproveVersionCommand(new VersionId(versionId), lockVersion, new UserId(user))));
    }

    @PostMapping("/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable UUID versionId, @RequestParam int lockVersion) {
        review.discard(new DiscardDraftCommand(new VersionId(versionId), lockVersion));
    }

    @PostMapping("/publish")
    PublicationResponse publish(@PathVariable UUID versionId, @RequestBody PublishRequest request,
                                @RequestHeader(USER_HEADER) String user) {
        return PublicationResponse.from(publish.publish(
                new PublishVersionCommand(new VersionId(versionId), request.countryCodes(), new UserId(user))));
    }

    @GetMapping("/diff/{otherVersionId}")
    DiffResponse diff(@PathVariable UUID versionId, @PathVariable UUID otherVersionId) {
        return DiffResponse.from(queries.diff(new VersionId(versionId), new VersionId(otherVersionId)));
    }
}
