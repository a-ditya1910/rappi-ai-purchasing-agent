package com.rappi.buyer.policy;

import com.rappi.buyer.domain.Policy;
import com.rappi.buyer.repo.PolicyRepo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Retrieval over the buying policies.
 *
 * ponytail: seven policies. keyword overlap scored in java is the right rung -
 * cosine over embeddings is a fifteen line swap behind this same method, and a
 * vector database would be absurd at this size. the interface does not change
 * when the corpus does.
 *
 * Worth saying plainly rather than hiding: this is retrieval augmented
 * generation with the retrieval done by term overlap instead of embeddings. The
 * part that matters for the agent - policy text arriving at decision time,
 * cited by id, rather than baked into a prompt - is the same either way. And it
 * costs no llm quota, because the lookup never leaves this service.
 */
@Service
public class PolicyRetriever {

    public record Hit(String id, String title, String body, double score) {}

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "a", "an", "is", "are", "was", "were", "be", "to", "of", "and", "or",
            "in", "on", "for", "with", "that", "this", "it", "as", "at", "by", "from",
            "we", "should", "can", "do", "does", "what", "when", "how", "if", "not"));

    private final PolicyRepo policies;

    public PolicyRetriever(PolicyRepo policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public List<Hit> search(String query, int k) {
        Set<String> terms = tokens(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        List<Hit> hits = new ArrayList<>();
        for (Policy p : policies.findAll()) {
            Set<String> haystack = tokens(p.getTitle() + " " + p.getTags() + " " + p.getBody());
            long overlap = terms.stream().filter(haystack::contains).count();
            if (overlap == 0) {
                continue;
            }
            // tags are a stronger signal than body prose, so weight a tag hit higher
            Set<String> tagTerms = tokens(p.getTags());
            long tagHits = terms.stream().filter(tagTerms::contains).count();
            double score = (overlap + tagHits * 2.0) / terms.size();
            hits.add(new Hit(p.getId(), p.getTitle(), p.getBody(), round(score)));
        }

        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String t : text.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() > 2 && !STOP.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
