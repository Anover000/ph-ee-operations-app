package org.apache.fineract.operations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TimestampRepository extends JpaRepository<Timestamps, String>, JpaSpecificationExecutor {

}
