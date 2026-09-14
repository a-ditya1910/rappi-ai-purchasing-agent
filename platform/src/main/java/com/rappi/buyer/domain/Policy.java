package com.rappi.buyer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "policies")
@Getter
@Setter
@NoArgsConstructor
public class Policy {

    @Id
    private String id;

    private String title;
    private String body;
    private String tags;

    /** JSON array of floats. Cosine runs in java over ~25 rows, see PolicyRetriever. */
    private String embedding;
}
