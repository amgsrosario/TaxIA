package com.knowledgeflow.research.service;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.research.entity.CriticalMemoryEntry;
import com.knowledgeflow.research.entity.KnowledgeProposition;
import com.knowledgeflow.research.entity.KnowledgePropositionRelation;
import com.knowledgeflow.research.entity.ResearchEvidence;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.entity.EntailmentCheck;
import com.knowledgeflow.research.entity.ResearchStep;
import com.knowledgeflow.research.enums.CriticalClassification;
import com.knowledgeflow.research.enums.CriticalStatus;
import com.knowledgeflow.research.enums.EvidenceClassification;
import com.knowledgeflow.research.enums.InvestigationTrigger;
import com.knowledgeflow.research.enums.PropositionConfidence;
import com.knowledgeflow.research.enums.PropositionRelationType;
import com.knowledgeflow.research.enums.PropositionStatus;
import com.knowledgeflow.research.enums.ResearchPhase;
import com.knowledgeflow.research.enums.StepOrigin;
import com.knowledgeflow.research.enums.StepOutcome;
import com.knowledgeflow.research.enums.ValidityStatus;
import com.knowledgeflow.research.repository.KnowledgePropositionRelationRepository;
import com.knowledgeflow.research.repository.KnowledgePropositionRepository;
import com.knowledgeflow.research.repository.ResearchEvidenceRepository;
import com.knowledgeflow.research.repository.ResearchInvestigationRepository;
import com.knowledgeflow.research.repository.ResearchStepRepository;
import com.knowledgeflow.research.service.CriticalMemoryService.Observation;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.PropositionExtractor.EvidenceInput;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedCondition;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedConflict;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedProposition;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.source.ConsolidatedArticleParser;
import com.knowledgeflow.research.source.ConsolidatedArticleParser.ParsedArticle;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.OfficialLegislationCatalog;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import com.knowledgeflow.research.source.ResearchProperties;
import com.knowledgeflow.research.repository.EntailmentCheckRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Percurso Research → Evidence → Critical Memory → Structured Knowledge (KF-M01, ADR-010;
 * AR2-DEC-KF-2026-10-22 B1–B3 e Aditamento Operacional).
 * <ol>
 *   <li><b>Corpus:</b> só se investiga uma pergunta sem Q&amp;A publicada relevante.</li>
 *   <li><b>Descoberta:</b> referências explícitas da pergunta e hipóteses do modelo; hipóteses já
 *       desmentidas na memória crítica não são repetidas.</li>
 *   <li><b>Consulta e confirmação:</b> leitura read-only da fonte oficial na allowlist; a página
 *       tem de conter o artigo pedido.</li>
 *   <li><b>Evidência:</b> texto oficial com origem, contexto, SHA-256, vigência e classificação;
 *       nunca promovida.</li>
 *   <li><b>Memória crítica:</b> hipóteses desmentidas, referências não confirmadas, conteúdo revogado,
 *       extracções sem âncora, conflitos e respostas rejeitadas pelo grounding.</li>
 *   <li><b>Conhecimento estruturado:</b> proposições, condições e excepções com âncora literal.</li>
 *   <li><b>Resposta:</b> {@code CONSULTA_DOCUMENTADA_COM_LIMITACOES}/{@code SUGGESTED} ou
 *       {@code RESPOSTA_LIMITE} ({@link ResearchAnswerPolicy}).</li>
 * </ol>
 * Não publica, não indexa e não escreve em tabelas do corpus de Q&amp;A.
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final ResearchProperties properties;
    private final CorpusCoverageChecker corpusChecker;
    private final OfficialLegislationCatalog catalog;
    private final OfficialSourceClient sourceClient;
    private final ConsolidatedArticleParser parser;
    private final DiscoveryHypothesisProvider hypothesisProvider;
    private final PropositionExtractor extractor;
    private final AnchorVerifier anchorVerifier;
    private final CriticalMemoryService criticalMemory;
    private final ResearchAnswerPolicy policy;
    private final ResearchAnswerComposer composer;
    private final ResearchInvestigationRepository investigationRepository;
    private final ResearchStepRepository stepRepository;
    private final ResearchEvidenceRepository evidenceRepository;
    private final KnowledgePropositionRepository propositionRepository;
    private final KnowledgePropositionRelationRepository relationRepository;
    private final AuditService auditService;
    // Segunda barreira de implicação (ADR-010-A, A4). Só usada com knowledgeflow.research.entailment.enabled=true.
    private final SupportVerifier supportVerifier;
    private final ModelIdentity modelIdentity;
    private final EntailmentValidator entailmentValidator;
    private final EntailmentGate entailmentGate;
    private final EntailmentCheckRepository entailmentRepository;
    /** Datas civis em Europe/Lisbon (data de consulta das fontes e vigência). */
    private final Clock clock = Clock.system(ZoneId.of("Europe/Lisbon"));

    public InvestigationService(ResearchProperties properties, CorpusCoverageChecker corpusChecker,
            OfficialLegislationCatalog catalog, OfficialSourceClient sourceClient, ConsolidatedArticleParser parser,
            DiscoveryHypothesisProvider hypothesisProvider, PropositionExtractor extractor,
            AnchorVerifier anchorVerifier, CriticalMemoryService criticalMemory, ResearchAnswerPolicy policy,
            ResearchAnswerComposer composer, ResearchInvestigationRepository investigationRepository,
            ResearchStepRepository stepRepository, ResearchEvidenceRepository evidenceRepository,
            KnowledgePropositionRepository propositionRepository,
            KnowledgePropositionRelationRepository relationRepository, AuditService auditService,
            SupportVerifier supportVerifier, ModelIdentity modelIdentity, EntailmentValidator entailmentValidator,
            EntailmentGate entailmentGate, EntailmentCheckRepository entailmentRepository) {
        this.properties = properties;
        this.corpusChecker = corpusChecker;
        this.catalog = catalog;
        this.sourceClient = sourceClient;
        this.parser = parser;
        this.hypothesisProvider = hypothesisProvider;
        this.extractor = extractor;
        this.anchorVerifier = anchorVerifier;
        this.criticalMemory = criticalMemory;
        this.policy = policy;
        this.composer = composer;
        this.investigationRepository = investigationRepository;
        this.stepRepository = stepRepository;
        this.evidenceRepository = evidenceRepository;
        this.propositionRepository = propositionRepository;
        this.relationRepository = relationRepository;
        this.auditService = auditService;
        this.supportVerifier = supportVerifier;
        this.modelIdentity = modelIdentity;
        this.entailmentValidator = entailmentValidator;
        this.entailmentGate = entailmentGate;
        this.entailmentRepository = entailmentRepository;
    }

    /** Candidato de descoberta, com a sua origem. */
    private record Candidate(LegislationReference reference, StepOrigin origin, String rationale) {}

    /** Estado mutável de uma execução. */
    private final class Run {
        final ResearchInvestigation investigation;
        final UUID userId;
        int nextStep = 1;
        /** Chamadas ao validador de implicação nesta investigação (só com a barreira ligada). */
        int entailmentCalls = 0;
        /** Proposições com âncora literal cuja implicação não foi demonstrada (só com a barreira ligada). */
        int entailmentNotDemonstrated = 0;

        Run(ResearchInvestigation investigation, UUID userId) {
            this.investigation = investigation;
            this.userId = userId;
        }

        UUID org() {
            return investigation.getOrganizationId();
        }

        UUID id() {
            return investigation.getId();
        }

        ResearchStep step(ResearchPhase phase, StepOrigin origin, String target, String url, Integer httpStatus,
                StepOutcome outcome, String detail) {
            return stepRepository.save(new ResearchStep(id(), nextStep++, phase, origin, target, url, httpStatus,
                    outcome, detail, now()));
        }

        ResearchStep modelStep(ResearchPhase phase, String target, ModelCall call) {
            if (call == null) return null;
            return stepRepository.save(ResearchStep.modelCall(id(), nextStep++, phase, target, call, now()));
        }

        CriticalMemoryEntry remember(Observation observation) {
            return criticalMemory.record(org(), id(), userId, observation);
        }
    }

    public ResearchInvestigation investigate(UUID organizationId, UUID userId, String question) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN, "Investigação desligada (knowledgeflow.research.enabled=false)");
        }
        if (question == null || question.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "A pergunta é obrigatória");
        }
        String q = question.strip();

        CorpusCoverageChecker.Result coverage = corpusChecker.check(organizationId, q);
        InvestigationTrigger trigger = switch (coverage.coverage()) {
            case RELEVANT_QA_FOUND -> InvestigationTrigger.RELEVANT_QA_EXISTS;
            case NO_RELEVANT_QA -> InvestigationTrigger.NO_RELEVANT_QA;
            case UNAVAILABLE -> InvestigationTrigger.CORPUS_CHECK_UNAVAILABLE;
        };
        ResearchInvestigation investigation = investigationRepository.save(
                new ResearchInvestigation(organizationId, userId, q, trigger, now()));
        Run run = new Run(investigation, userId);
        run.step(ResearchPhase.CORPUS_CHECK, StepOrigin.CORPUS, null, null, null, switch (coverage.coverage()) {
            case RELEVANT_QA_FOUND -> StepOutcome.RELEVANT_QA_FOUND;
            case NO_RELEVANT_QA -> StepOutcome.NO_RELEVANT_QA;
            case UNAVAILABLE -> StepOutcome.FAILED;
        }, coverage.detail());

        if (trigger == InvestigationTrigger.RELEVANT_QA_EXISTS) {
            auditService.record(organizationId, userId, AuditAction.RESEARCH_INVESTIGATION_NOT_STARTED,
                    "RESEARCH_INVESTIGATION", investigation.getId(), "reason=RELEVANT_QA_EXISTS");
            return investigation;
        }
        auditService.record(organizationId, userId, AuditAction.RESEARCH_INVESTIGATION_STARTED,
                "RESEARCH_INVESTIGATION", investigation.getId(), "trigger=" + trigger);

        try {
            List<ResearchEvidence> evidence = research(run, discover(run, q));
            List<KnowledgeProposition> propositions = structure(run, q, evidence);
            answer(run, q, evidence, propositions);
            auditService.record(organizationId, userId, AuditAction.RESEARCH_INVESTIGATION_COMPLETED,
                    "RESEARCH_INVESTIGATION", investigation.getId(),
                    "answerType=%s;parecer=%s".formatted(investigation.getAnswerType(), investigation.getParecerRequirement()));
        } catch (RuntimeException e) {
            log.warn("Investigação {} falhou: {}", investigation.getId(), e.toString());
            investigation.fail("Falha técnica: " + e.getClass().getSimpleName(), now());
            investigationRepository.save(investigation);
            auditService.record(organizationId, userId, AuditAction.RESEARCH_INVESTIGATION_FAILED,
                    "RESEARCH_INVESTIGATION", investigation.getId(), "error=" + e.getClass().getSimpleName());
        }
        return investigationRepository.findById(investigation.getId()).orElse(investigation);
    }

    // -------------------------------------------------------------------------
    // Descoberta
    // -------------------------------------------------------------------------

    private List<Candidate> discover(Run run, String question) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (LegislationReference ref : catalog.referencesIn(question)) {
            candidates.putIfAbsent(ref.key(), new Candidate(ref, StepOrigin.QUESTION_REFERENCE, "Referida na pergunta"));
            run.step(ResearchPhase.DISCOVERY, StepOrigin.QUESTION_REFERENCE, ref.display(), null, null,
                    StepOutcome.PROPOSED, "Referência explícita na pergunta");
        }
        for (Hypothesis h : hypothesisProvider.propose(question, catalog.consultableCodes(), properties.getMaxModelHypotheses())) {
            if (candidates.containsKey(h.reference().key())) continue;
            candidates.put(h.reference().key(), new Candidate(h.reference(), StepOrigin.MODEL_HYPOTHESIS, h.rationale()));
            run.step(ResearchPhase.DISCOVERY, StepOrigin.MODEL_HYPOTHESIS, h.reference().display(), null, null,
                    StepOutcome.PROPOSED, "Hipótese do modelo (não é evidência): " + nullToDash(h.rationale()));
        }

        // Memória crítica: não repetir hipóteses do modelo já desmentidas na fonte oficial.
        Map<String, List<CriticalMemoryEntry>> known = criticalMemory
                .findByReferenceKeys(run.org(), candidates.keySet()).stream()
                .collect(Collectors.groupingBy(CriticalMemoryEntry::getReferenceKey));
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            CriticalMemoryEntry refuted = known.getOrDefault(c.reference().key(), List.of()).stream()
                    .filter(e -> e.getClassification() == CriticalClassification.ERRONEOUS
                            && e.getStatus() == CriticalStatus.ACTIVE)
                    .findFirst().orElse(null);
            if (refuted != null && c.origin() == StepOrigin.MODEL_HYPOTHESIS) {
                run.remember(new Observation(refuted.getClaim(), CriticalClassification.ERRONEOUS,
                        "Hipótese recorrente já desmentida; não consultada de novo", refuted.getProvenance(),
                        refuted.getReferenceKey(), refuted.getSourceUrl(), refuted.getSourceVersion(),
                        null, null, null, null));
                run.step(ResearchPhase.DISCOVERY, StepOrigin.CRITICAL_MEMORY, c.reference().display(), null, null,
                        StepOutcome.SKIPPED, "Memória crítica: " + refuted.getReason());
                continue;
            }
            kept.add(c);
        }
        return kept;
    }

    // -------------------------------------------------------------------------
    // Consulta, confirmação e evidência
    // -------------------------------------------------------------------------

    private List<ResearchEvidence> research(Run run, List<Candidate> candidates) {
        List<ResearchEvidence> evidence = new ArrayList<>();
        int consultations = 0;
        for (Candidate c : candidates) {
            LegislationReference ref = c.reference();
            // Fonte suspensa (ponto 4.1): não é consultada nem pode originar evidência; fica o passo com o motivo.
            Optional<String> suspension = catalog.sourceSuspension(ref.code());
            if (suspension.isPresent()) {
                run.step(ResearchPhase.CONSULTATION, StepOrigin.OFFICIAL_SOURCE, ref.display(), null, null,
                        StepOutcome.SKIPPED, suspension.get());
                continue;
            }
            String url = catalog.urlFor(ref).orElse(null);
            if (url == null) {
                run.step(ResearchPhase.CONSULTATION, StepOrigin.OFFICIAL_SOURCE, ref.display(), null, null,
                        StepOutcome.SKIPPED, "Código fora do catálogo de fontes oficiais");
                continue;
            }
            if (consultations >= properties.getMaxConsultations()) {
                run.step(ResearchPhase.CONSULTATION, StepOrigin.OFFICIAL_SOURCE, ref.display(), url, null,
                        StepOutcome.SKIPPED, "Limite de consultas por investigação atingido");
                continue;
            }
            consultations++;
            OffsetDateTime retrievedAt = now();
            FetchedPage page = sourceClient.fetch(url);
            StepOutcome outcome = switch (page.outcome()) {
                case FETCHED -> StepOutcome.FETCHED;
                case NOT_FOUND -> StepOutcome.NOT_FOUND;
                case BLOCKED -> StepOutcome.BLOCKED;
                case FAILED -> StepOutcome.FAILED;
            };
            // Defesa em profundidade (ponto 4.1): uma página cujo URL final pertence a uma fonte suspensa (por exemplo,
            // depois de uma redirecção) nunca é analisada nem origina evidência, seja qual for o cliente.
            if (outcome == StepOutcome.FETCHED && page.finalUrl() != null) {
                String finalPath = java.net.URI.create(page.finalUrl()).getPath();
                if (OfficialLegislationCatalog.isSuspendedSourcePath(finalPath)) {
                    outcome = StepOutcome.BLOCKED;
                    page = FetchedPage.blocked(page.httpStatus(), page.finalUrl(),
                            "Fonte suspensa (redacção histórica): página recusada como evidência");
                }
            }
            ResearchStep consultation = run.step(ResearchPhase.CONSULTATION, StepOrigin.OFFICIAL_SOURCE,
                    ref.display(), page.finalUrl() == null ? url : page.finalUrl(), page.httpStatus(), outcome,
                    page.detail());

            if (outcome == StepOutcome.NOT_FOUND) {
                run.remember(refutedDiscovery(c, url, true, "A fonte oficial não tem a página do " + ref.display()
                        + " (" + page.detail() + ")"));
                continue;
            }
            if (outcome != StepOutcome.FETCHED) {
                // Bloqueio, falha técnica ou resposta recusada (ex.: acima do limite de tamanho):
                // sem conteúdo a avaliar, nenhuma evidência é criada; fica o passo e a auditoria.
                auditService.record(run.org(), run.userId, AuditAction.RESEARCH_CONSULTATION_REJECTED,
                        "RESEARCH_INVESTIGATION", run.id(), "step=%s;outcome=%s;http=%s;detail=%s".formatted(
                                consultation.getId(), outcome, page.httpStatus(), page.detail()));
                continue;
            }

            ParsedArticle article = parser.parse(page.body(), ref, retrievedAt.toLocalDate());
            if (!article.confirmed()) {
                run.step(ResearchPhase.CONFIRMATION, StepOrigin.OFFICIAL_SOURCE, ref.display(), consultation.getUrl(),
                        null, StepOutcome.NOT_CONFIRMED, article.detail());
                run.remember(refutedDiscovery(c, consultation.getUrl(), false,
                        "A fonte oficial não confirma: " + article.detail()));
                continue;
            }
            String sha = CriticalMemoryService.sha256(article.content());
            run.step(ResearchPhase.CONFIRMATION, StepOrigin.OFFICIAL_SOURCE, ref.display(), consultation.getUrl(),
                    null, StepOutcome.CONFIRMED, "%s confirmado; sha256=%s; vigência=%s"
                            .formatted(article.heading(), sha, article.validityStatus()));

            boolean revoked = article.validityStatus() == ValidityStatus.REVOKED;
            ResearchEvidence e = evidenceRepository.save(new ResearchEvidence(run.org(), run.id(), consultation.getId(),
                    KnowledgeSourceType.LEGISLATION, catalog.authority(), ref.key(), ref.display(),
                    article.heading() + (article.epigraph() == null ? "" : " — " + article.epigraph()),
                    consultation.getUrl(), retrievedAt, sha, article.content(),
                    article.amendmentNotes().isEmpty() ? null : String.join("\n", article.amendmentNotes()),
                    article.validityStatus(), article.validityReason(),
                    revoked ? EvidenceClassification.REJECTED : EvidenceClassification.ACCEPTED,
                    revoked ? "Artigo revogado no texto consolidado: não pode fundamentar"
                            : "Fonte oficial autorizada (D2/B2), artigo confirmado na página consultada",
                    now()));
            auditService.record(run.org(), run.userId, AuditAction.RESEARCH_EVIDENCE_RECORDED, "RESEARCH_EVIDENCE",
                    e.getId(), "reference=%s;classification=%s;validity=%s;sha256=%s"
                            .formatted(e.getReferenceKey(), e.getClassification(), e.getValidityStatus(), sha));

            if (revoked) {
                run.remember(new Observation(ref.display() + " está em vigor", CriticalClassification.SUPERSEDED,
                        "O texto consolidado indica o artigo como revogado", provenance(e), ref.key(), e.getUrl(),
                        "sha256:" + sha, e.getId(), null, null, null));
            }
            for (String item : article.revokedItems()) {
                run.remember(new Observation("%s, %s %s está em vigor".formatted(ref.display(),
                                item.matches("\\d+") ? "n.º" : "alínea", item),
                        CriticalClassification.SUPERSEDED, "Revogado segundo o texto consolidado",
                        provenance(e), ref.key(), e.getUrl(), "sha256:" + sha, e.getId(), null, null, null));
            }
            evidence.add(e);
        }
        return evidence;
    }

    /**
     * Só uma hipótese do modelo cujo artigo não existe na fonte oficial é ERRONEOUS (e deixa de ser
     * consultada em investigações futuras); uma página que não confirma o artigo, ou uma referência
     * da própria pergunta, fica INSUFFICIENT.
     */
    private Observation refutedDiscovery(Candidate c, String url, boolean sourceAbsent, String reason) {
        boolean modelHypothesis = c.origin() == StepOrigin.MODEL_HYPOTHESIS;
        String claim = modelHypothesis
                ? "Hipótese de descoberta: o %s regula a matéria investigada".formatted(c.reference().display())
                : "A pergunta refere o %s".formatted(c.reference().display());
        return new Observation(claim,
                modelHypothesis && sourceAbsent ? CriticalClassification.ERRONEOUS : CriticalClassification.INSUFFICIENT,
                reason,
                (modelHypothesis ? "Hipótese do modelo" : "Referência da pergunta") + "; consulta a " + url,
                c.reference().key(), url, null, null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Conhecimento estruturado
    // -------------------------------------------------------------------------

    private List<KnowledgeProposition> structure(Run run, String question, List<ResearchEvidence> evidence) {
        List<ResearchEvidence> accepted = evidence.stream().filter(ResearchEvidence::isUsableAsFoundation).toList();
        if (accepted.isEmpty()) return List.of();

        Map<String, ResearchEvidence> byLabel = new LinkedHashMap<>();
        List<EvidenceInput> inputs = new ArrayList<>();
        for (int i = 0; i < accepted.size(); i++) {
            ResearchEvidence e = accepted.get(i);
            String label = "E" + (i + 1);
            byLabel.put(label, e);
            inputs.add(new EvidenceInput(label, e.getLegalReference(), e.getContent()));
        }
        Extraction extraction = extractor.extract(question, inputs);

        List<KnowledgeProposition> propositions = new ArrayList<>();
        for (ExtractedProposition p : extraction.propositions()) {
            ResearchEvidence e = byLabel.get(p.evidenceLabel());
            if (e == null && accepted.size() == 1) e = accepted.get(0);
            propositions.add(e == null ? null : recordProposition(run, question, p, e));
            if (e == null) {
                run.remember(new Observation(p.statement(), CriticalClassification.REJECTED,
                        "Proposição extraída sem indicação de evidência válida", "Extracção pelo modelo",
                        null, null, null, null, null, null, null));
            }
        }
        recordConflicts(run, extraction.conflicts(), propositions);
        return propositions.stream().filter(p -> p != null).toList();
    }

    /**
     * Decisão por proposição. Com a barreira de implicação desligada (omissão), executa exactamente a decisão actual
     * (âncora literal e números; resultados, passos, auditoria e memória crítica iguais aos de antes do A4). Com ela
     * ligada, aplica a orquestração do piloto, adaptada (delimitação v3, A.3/A.4).
     */
    private KnowledgeProposition recordProposition(Run run, String question, ExtractedProposition p, ResearchEvidence e) {
        if (!properties.getEntailment().isEnabled()) return recordPropositionWithoutBarrier(run, p, e);
        return recordPropositionWithBarrier(run, question, p, e);
    }

    /** Decisão actual do {@code main}, sem alterações (barreira desligada). */
    private KnowledgeProposition recordPropositionWithoutBarrier(Run run, ExtractedProposition p, ResearchEvidence e) {
        String referenceAndContent = e.getLegalReference() + "\n" + e.getContent();
        boolean anchored = anchorVerifier.isAnchored(p.anchor(), e.getContent())
                && anchorVerifier.numbersSupported(p.statement(), referenceAndContent);

        List<KnowledgeProposition.Condition> conditions = new ArrayList<>();
        List<ExtractedCondition> unanchoredConditions = new ArrayList<>();
        for (ExtractedCondition c : p.conditions()) {
            boolean ok = anchorVerifier.isAnchored(c.anchor(), e.getContent())
                    && anchorVerifier.numbersSupported(c.description(), referenceAndContent);
            conditions.add(new KnowledgeProposition.Condition(c.kind(), c.description(), c.anchor(), ok));
            if (!ok) unanchoredConditions.add(c);
        }

        KnowledgeProposition proposition = propositionRepository.save(new KnowledgeProposition(run.org(), run.id(),
                p.kind(), p.statement(), e.getLegalReference(), p.scope(), p.applicability(), e.getValidityStatus(),
                anchored ? PropositionConfidence.ANCHORED : PropositionConfidence.UNSUPPORTED,
                anchored ? PropositionStatus.CANDIDATE : PropositionStatus.REJECTED,
                anchored ? "Âncora literal verificada na evidência; não promovida (B4 bloqueada)"
                        : "Sem âncora literal na evidência, ou números ausentes da evidência",
                List.of(new KnowledgeProposition.Support(e.getId(), p.anchor(), anchored)), conditions, now()));
        auditService.record(run.org(), run.userId, AuditAction.KNOWLEDGE_PROPOSITION_RECORDED, "KNOWLEDGE_PROPOSITION",
                proposition.getId(), "status=%s;confidence=%s;evidence=%s"
                        .formatted(proposition.getStatus(), proposition.getConfidence(), e.getId()));

        if (!anchored) {
            run.remember(new Observation(p.statement(), CriticalClassification.REJECTED,
                    "Afirmação extraída sem âncora literal na evidência oficial (ou com números ausentes dela)",
                    "Extracção pelo modelo sobre " + provenance(e), e.getReferenceKey(), e.getUrl(),
                    "sha256:" + e.getContentSha256(), e.getId(), proposition.getId(), null, null));
        }
        for (ExtractedCondition c : unanchoredConditions) {
            run.remember(new Observation((c.kind().name().equals("EXCEPTION") ? "Excepção: " : "Condição: ")
                    + c.description(), CriticalClassification.REJECTED,
                    "Condição/excepção extraída sem âncora literal na evidência oficial",
                    "Extracção pelo modelo sobre " + provenance(e), e.getReferenceKey(), e.getUrl(),
                    "sha256:" + e.getContentSha256(), e.getId(), proposition.getId(), null, null));
        }
        return proposition;
    }

    /**
     * Decisão com a segunda barreira (ADR-010-A, A4; orquestração do piloto {@code ac84c04}, adaptada):
     * presença literal, suporte substantivo ({@link SupportVerifier}) e implicação jurídica ({@link EntailmentGate}),
     * fail-closed. Adaptação A.3: um diploma sem identificação verificada no catálogo (o CIRC) dá «não demonstrada»
     * por decisão local, antes do {@code SupportVerifier} e do validador, registada no rasto.
     */
    private KnowledgeProposition recordPropositionWithBarrier(Run run, String question, ExtractedProposition p, ResearchEvidence e) {
        String referenceAndContent = e.getLegalReference() + "\n" + e.getContent();
        String code = e.getReferenceKey() == null ? "" : e.getReferenceKey().split(":")[0];
        Optional<String> identification = catalog.identification(code);
        // Condições/excepções primeiro: o suporte da afirmação depende de as ter estruturadas (KF-M02/CORR-04).
        // Sem identificação verificada, as condições ficam só com a verificação literal (não sustentam nada: a
        // proposição é «não demonstrada» por decisão local).
        List<KnowledgeProposition.Condition> conditions = new ArrayList<>();
        List<ExtractedCondition> unanchoredConditions = new ArrayList<>();
        for (ExtractedCondition c : p.conditions()) {
            boolean ok = anchorVerifier.isAnchored(c.anchor(), e.getContent(), c.description())
                    && anchorVerifier.numbersSupported(c.description(), referenceAndContent)
                    && (identification.isEmpty() || supportVerifier.checkCondition(c.description(), c.anchor(),
                            e.getContent(), e.getLegalReference(), identification.get()).supported());
            conditions.add(new KnowledgeProposition.Condition(c.kind(), c.description(), c.anchor(), ok));
            if (!ok) unanchoredConditions.add(c);
        }
        List<KnowledgeProposition.Condition> verifiedConditions = conditions.stream().filter(KnowledgeProposition.Condition::isAnchored).toList();
        List<String> conditionAnchors = verifiedConditions.stream().map(KnowledgeProposition.Condition::getAnchorText).toList();

        boolean literal = anchorVerifier.isAnchored(p.anchor(), e.getContent(), p.statement())
                && anchorVerifier.numbersSupported(p.statement(), referenceAndContent);
        boolean identificationMissing = literal && identification.isEmpty();
        SupportVerifier.Result support = literal && !identificationMissing
                ? supportVerifier.check(p.statement(), p.anchor(), e.getContent(), e.getLegalReference(), identification.get(),
                        conditionAnchors)
                : new SupportVerifier.Result(false, 0.0, List.of());
        boolean deterministic = literal && !identificationMissing && support.supported();
        // A barreira só actua sobre o que as verificações determinísticas não rejeitaram: nunca converte uma rejeição
        // em aprovação. Decisões locais «não demonstrada» não chamam o validador.
        Entailment entailment = identificationMissing
                ? new Entailment(null, EntailmentGate.notDemonstrated(
                        "diploma sem identificação verificada no catálogo: " + code), null)
                : deterministic ? validateEntailment(run, question, p, e, verifiedConditions) : null;
        boolean usable = deterministic && entailment.decision().demonstrated();
        if (identificationMissing || (deterministic && !usable)) run.entailmentNotDemonstrated++;

        String coverage = "cobertura %d%%".formatted(Math.round(support.coverage() * 100));
        String statusReason = usable
                ? "Âncora literal com suporte substantivo na unidade legal (" + coverage + ") e implicação jurídica "
                        + "demonstrada pela segunda barreira (" + entailment.decision().reason() + "); não promovida (B4 bloqueada)"
                : identificationMissing
                        ? "Âncora literal presente, mas implicação não demonstrada: " + entailment.decision().reason()
                : deterministic
                        ? "Âncora literal e verificações determinísticas sem objecção (" + coverage + "), mas implicação "
                                + "jurídica não demonstrada pela segunda barreira: " + entailment.decision().reason()
                : literal
                        ? "Âncora literal presente, mas sem suporte substantivo na unidade legal (" + coverage
                                + "; " + String.join("; ", support.reasons())
                                + (support.uncoveredTerms().isEmpty() ? "" : "; termos sem cobertura: "
                                        + String.join(", ", support.uncoveredTerms())) + ")"
                        : "Sem âncora literal na evidência, ou números ausentes da evidência";
        KnowledgeProposition proposition = propositionRepository.save(new KnowledgeProposition(run.org(), run.id(),
                p.kind(), p.statement(), e.getLegalReference(), p.scope(), p.applicability(), e.getValidityStatus(),
                usable ? PropositionConfidence.ANCHORED : PropositionConfidence.UNSUPPORTED,
                usable ? PropositionStatus.CANDIDATE : PropositionStatus.REJECTED,
                statusReason,
                List.of(new KnowledgeProposition.Support(e.getId(), p.anchor(), literal)), conditions, now()));
        if (entailment != null) recordEntailment(run, entailment, proposition, e);
        auditService.record(run.org(), run.userId, AuditAction.KNOWLEDGE_PROPOSITION_RECORDED, "KNOWLEDGE_PROPOSITION",
                proposition.getId(), "status=%s;confidence=%s;evidence=%s;entailment=%s"
                        .formatted(proposition.getStatus(), proposition.getConfidence(), e.getId(),
                                entailment == null ? "NOT_EVALUATED" : entailment.decision().decision()));

        if (literal && !usable) {
            run.remember(new Observation(p.statement(), CriticalClassification.INSUFFICIENT, statusReason,
                    "Extracção pelo modelo sobre " + provenance(e), e.getReferenceKey(), e.getUrl(),
                    "sha256:" + e.getContentSha256(), e.getId(), proposition.getId(), null, null));
        }
        if (!literal) {
            run.remember(new Observation(p.statement(), CriticalClassification.REJECTED,
                    "Afirmação extraída sem âncora literal na evidência oficial (ou com números ausentes dela)",
                    "Extracção pelo modelo sobre " + provenance(e), e.getReferenceKey(), e.getUrl(),
                    "sha256:" + e.getContentSha256(), e.getId(), proposition.getId(), null, null));
        }
        for (ExtractedCondition c : unanchoredConditions) {
            run.remember(new Observation((c.kind().name().equals("EXCEPTION") ? "Excepção: " : "Condição: ")
                    + c.description(), CriticalClassification.REJECTED,
                    "Condição/excepção extraída sem âncora literal ou sem suporte substantivo na evidência oficial",
                    "Extracção pelo modelo sobre " + provenance(e), e.getReferenceKey(), e.getUrl(),
                    "sha256:" + e.getContentSha256(), e.getId(), proposition.getId(), null, null));
        }
        return proposition;
    }

    /** Resultado da barreira para uma proposição: avaliação do validador (nula nas decisões locais) e decisão final. */
    private record Entailment(EntailmentValidator.Assessment assessment, EntailmentGate.Decision decision,
            ResearchStep modelStep) {}

    /** Decisões locais «não demonstrada» (sem chamada) e, nos restantes casos, uma chamada pela porta do modelo. */
    private Entailment validateEntailment(Run run, String question, ExtractedProposition p, ResearchEvidence e,
            List<KnowledgeProposition.Condition> verifiedConditions) {
        List<String> verifiedConditionAnchors = verifiedConditions.stream().map(KnowledgeProposition.Condition::getAnchorText).toList();
        if (!entailmentValidator.isAvailable()) {
            return new Entailment(null, EntailmentGate.notDemonstrated(
                    "segunda barreira de implicação indisponível (validador desligado)"), null);
        }
        int maxChecks = properties.getEntailment().effectiveMaxChecks();
        if (maxChecks == 0) {
            return new Entailment(null, EntailmentGate.notDemonstrated(
                    "max-checks inválido (" + properties.getEntailment().getMaxChecks() + "): nenhuma validação permitida"), null);
        }
        if (run.entailmentCalls >= maxChecks) {
            return new Entailment(null, EntailmentGate.notDemonstrated("limite de validações de implicação por investigação "
                    + "atingido (" + maxChecks + "; configurado " + properties.getEntailment().getMaxChecks() + ", limite KF-M02 "
                    + ResearchProperties.Entailment.KF_M02_MAX_CHECKS_LIMIT + ")"), null);
        }
        if (!SupportVerifier.occurrenceIdentified(p.anchor(), e.getContent())) {
            return new Entailment(null, EntailmentGate.notDemonstrated(
                    "ocorrência da âncora não identificável: texto repetido ou janela que atravessa outra unidade legal"), null);
        }
        Optional<String> lostContext = NormativeContextGuard.violation(p.statement(), verifiedConditionAnchors,
                SupportVerifier.normativeUnit(p.anchor(), e.getContent()));
        if (lostContext.isPresent()) return new Entailment(null, EntailmentGate.notDemonstrated(lostContext.get()), null);
        run.entailmentCalls++;
        String unit = SupportVerifier.unitContext(p.anchor(), e.getContent());
        EntailmentValidator.Request request = new EntailmentValidator.Request(question, p.statement(),
                // Só condições verificadas: o modelo não recebe o que a validação rejeitou.
                verifiedConditions.stream().map(c -> new EntailmentValidator.Qualifier(c.getKind().name(), c.getDescription())).toList(),
                e.getLegalReference(), unit, e.getContent());
        EntailmentValidator.Assessment assessment;
        try {
            assessment = entailmentValidator.assess(request);
        } catch (RuntimeException ex) {
            log.warn("Validação de implicação falhou: {}", ex.getClass().getSimpleName());
            assessment = EntailmentValidator.Assessment.protocolFailure("falha do validador",
                    ModelCall.failed(modelIdentity.current(), ex));
        }
        ResearchStep modelStep = run.modelStep(ResearchPhase.ENTAILMENT, "Validação de implicação: " + abbreviate(p.statement()),
                assessment == null ? null : assessment.call());
        return new Entailment(assessment, entailmentGate.decide(assessment, p.statement(), e.getContent(), unit,
                SupportVerifier.contiguousUnit(p.anchor(), e.getContent()), SupportVerifier.normativeUnit(p.anchor(), e.getContent()),
                verifiedConditionAnchors), modelStep);
    }

    /** Registo da barreira: linha em {@code research_entailment_checks} e passo VERIFICATION, também nas decisões locais. */
    private void recordEntailment(Run run, Entailment entailment, KnowledgeProposition proposition, ResearchEvidence e) {
        EntailmentValidator.Assessment a = entailment.assessment();
        EntailmentGate.Decision d = entailment.decision();
        entailmentRepository.save(new EntailmentCheck(run.id(), proposition.getId(), e.getId(),
                entailment.modelStep() == null ? null : entailment.modelStep().getId(),
                a == null ? null : a.verdict(), a == null ? null : a.dimensions(), a == null ? null : a.notes(),
                a == null ? null : a.justification(), a == null ? null : a.citedPassage(), d.citationVerified(),
                d.decision(), d.reason(), now()));
        run.step(ResearchPhase.ENTAILMENT, StepOrigin.VERIFICATION, abbreviate(proposition.getStatement()), e.getUrl(), null,
                a == null ? StepOutcome.SKIPPED : d.demonstrated() ? StepOutcome.CONFIRMED : StepOutcome.NOT_CONFIRMED,
                "proposição=%s; evidência=%s; veredicto=%s; passagem verificada=%s; decisão=%s: %s".formatted(
                        proposition.getId(), e.getId(), a == null || a.verdict() == null ? "—" : a.verdict(),
                        d.citationVerified(), d.decision(), d.reason()));
    }

    private static String abbreviate(String s) {
        return s == null ? null : s.length() <= 200 ? s : s.substring(0, 199) + "…";
    }

    private void recordConflicts(Run run, List<ExtractedConflict> conflicts, List<KnowledgeProposition> propositions) {
        for (ExtractedConflict conflict : conflicts) {
            KnowledgeProposition a = propositions.get(conflict.first());
            KnowledgeProposition b = propositions.get(conflict.second());
            // Só um conflito entre proposições ancoradas é material: uma proposição sem âncora já está rejeitada.
            if (a == null || b == null || !a.isUsableAsFoundation() || !b.isUsableAsFoundation()) continue;
            String note = conflict.note() == null ? "Conflito indicado na estruturação" : conflict.note();
            relationRepository.save(new KnowledgePropositionRelation(a.getId(), b.getId(),
                    PropositionRelationType.CONFLICTS_WITH, note, now()));
            for (KnowledgeProposition[] pair : new KnowledgeProposition[][] {{a, b}, {b, a}}) {
                KnowledgeProposition self = pair[0];
                KnowledgeProposition other = pair[1];
                self.quarantine("Conflito não resolvido com a proposição " + other.getId(), now());
                propositionRepository.save(self);
                UUID evidenceId = self.getSupports().get(0).getEvidenceId();
                UUID otherEvidenceId = other.getSupports().get(0).getEvidenceId();
                if (!evidenceId.equals(otherEvidenceId)) {
                    evidenceRepository.findById(evidenceId).ifPresent(ev -> {
                        ev.markContradicted("Contraditada por evidência " + otherEvidenceId + ": " + note);
                        evidenceRepository.save(ev);
                    });
                }
                run.remember(new Observation(self.getStatement(), CriticalClassification.CONTRADICTORY,
                        "Conflito não resolvido: " + note, "Proposição " + self.getId() + " (" + self.getFoundation() + ")",
                        null, null, null, evidenceId, self.getId(), otherEvidenceId, other.getId()));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Resposta
    // -------------------------------------------------------------------------

    private void answer(Run run, String question, List<ResearchEvidence> evidence, List<KnowledgeProposition> propositions) {
        Map<UUID, ResearchEvidence> current = new HashMap<>();
        evidenceRepository.findByInvestigationIdOrderByCreatedAtAsc(run.id()).forEach(e -> current.put(e.getId(), e));
        List<ResearchEvidence> accepted = evidence.stream()
                .map(e -> current.getOrDefault(e.getId(), e))
                .filter(ResearchEvidence::isUsableAsFoundation).toList();
        List<KnowledgeProposition> usable = propositions.stream().filter(KnowledgeProposition::isUsableAsFoundation).toList();
        boolean conflict = propositions.stream().anyMatch(p -> p.getStatus() == PropositionStatus.QUARANTINED);
        boolean uncertain = usable.stream().anyMatch(p -> p.getValidityStatus() != ValidityStatus.CONSOLIDATED_AT_RETRIEVAL)
                || (usable.isEmpty() && accepted.stream()
                        .anyMatch(e -> e.getValidityStatus() != ValidityStatus.CONSOLIDATED_AT_RETRIEVAL));

        ResearchAnswerPolicy.Decision decision = policy.decide(
                new ResearchAnswerPolicy.Inputs(accepted.size(), usable.size(), conflict, uncertain,
                        run.entailmentNotDemonstrated));

        Set<UUID> usedEvidenceIds = usable.stream()
                .flatMap(p -> p.getSupports().stream())
                .filter(KnowledgeProposition.Support::isAnchored)
                .map(KnowledgeProposition.Support::getEvidenceId)
                .collect(Collectors.toSet());
        List<ResearchEvidence> used = accepted.stream().filter(e -> usedEvidenceIds.contains(e.getId())).toList();

        String text;
        if (decision.isLimit()) {
            text = limitText(decision, evidence);
        } else {
            ResearchAnswerComposer.ComposedAnswer composed = composer.compose(question, used, usable);
            if (composed.validation().rejected()) {
                run.remember(new Observation(composed.text(), CriticalClassification.REJECTED,
                        "Resposta gerada rejeitada pelo grounding: " + composed.validation().rejectionReason(),
                        "Resposta do modelo (" + composed.response().provider() + ")", null, null, null,
                        null, null, null, null));
                decision = policy.groundingRejected();
                text = limitText(decision, evidence);
            } else {
                text = composed.text();
            }
        }

        List<String> limitations = new ArrayList<>();
        limitations.add("Fundamentação em EVIDÊNCIA DE INVESTIGAÇÃO NÃO PROMOVIDA: não foi validada nem publicada "
                + "como conhecimento institucional.");
        for (ResearchEvidence e : decision.isLimit() ? accepted : used) {
            limitations.add("%s: %s.".formatted(e.getLegalReference(), e.getValidityReason()));
        }
        limitations.add("Perímetro desta investigação: códigos consolidados da AT ("
                + String.join(", ", catalog.consultableCodes().stream().sorted().toList()) + "); "
                + "jurisprudência e outras fontes não foram consultadas.");
        if (!OfficialLegislationCatalog.SUSPENDED_SOURCES.isEmpty()) {
            limitations.add("Fontes suspensas, não consultadas nem usadas como fundamento: "
                    + String.join("; ", OfficialLegislationCatalog.SUSPENDED_SOURCES.values()) + ".");
        }
        List<String> assumptions = decision.isLimit() ? List.of() : usable.stream()
                .flatMap(p -> p.getConditions().stream())
                .filter(KnowledgeProposition.Condition::isAnchored)
                .map(c -> (c.getKind().name().equals("EXCEPTION") ? "Confirmar que não se aplica a excepção: "
                        : "Confirmar a condição: ") + c.getDescription())
                .distinct().toList();

        run.investigation.complete(decision.answerType(), decision.parecerRequirement(), text,
                String.join("\n", decision.reasons()), String.join("\n", limitations), String.join("\n", assumptions), now());
        investigationRepository.save(run.investigation);
    }

    private static String limitText(ResearchAnswerPolicy.Decision decision, List<ResearchEvidence> evidence) {
        StringBuilder sb = new StringBuilder(
                "Não é possível apresentar uma conclusão segura com base na investigação realizada.\n");
        decision.reasons().forEach(r -> sb.append("- ").append(r).append('\n'));
        if (!evidence.isEmpty()) {
            sb.append("Fontes oficiais consultadas:\n");
            evidence.forEach(e -> sb.append("- ").append(e.getLegalReference()).append(" (").append(e.getUrl())
                    .append(", consultada em ").append(e.getRetrievedAt().toLocalDate()).append(")\n"));
        }
        sb.append("Recomenda-se o pedido de parecer a um profissional qualificado.");
        return sb.toString();
    }

    private static String provenance(ResearchEvidence e) {
        return "%s, %s, consultado em %s (%s)".formatted(e.getLegalReference(), e.getUrl(), e.getRetrievedAt(),
                e.getAuthority());
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
