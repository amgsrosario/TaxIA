package com.knowledgeflow.research.service;

import com.knowledgeflow.research.source.LegislationReference;
import java.util.List;
import java.util.Set;

/**
 * Descoberta: propõe artigos possivelmente relevantes para uma pergunta. Uma hipótese nunca é
 * evidência; só passa a sê-lo depois de consultada e confirmada na fonte oficial.
 */
public interface DiscoveryHypothesisProvider {

    record Hypothesis(LegislationReference reference, String rationale) {}

    List<Hypothesis> propose(String question, Set<String> allowedCodes, int max);
}
