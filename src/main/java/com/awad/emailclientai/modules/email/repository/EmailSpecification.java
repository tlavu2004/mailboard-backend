package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

public class EmailSpecification {

    public static Specification<EmailEntity> filterEmails(Long accountId, EmailStatus status, Boolean unread, Boolean hasAttachments) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Account Selection (Mandatory)
            if (accountId != null) {
                predicates.add(criteriaBuilder.equal(root.get("account").get("id"), accountId));
            }

            // 2. Status Filter (Optional - e.g. for Kanban columns)
            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }

            // 3. Unread Filter (Optional)
            // If unread=true, we want isRead=false
            if (unread != null && unread) {
                predicates.add(criteriaBuilder.isFalse(root.get("isRead")));
            }

            // 4. Attachment Filter (Optional)
            if (hasAttachments != null && hasAttachments) {
                predicates.add(criteriaBuilder.isTrue(root.get("hasAttachments")));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
