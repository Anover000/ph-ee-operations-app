package org.apache.fineract.operations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EventTimestampsRepository extends JpaRepository<EventTimestamps, String>, JpaSpecificationExecutor {

}
