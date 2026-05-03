package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.MailMessageDetailDto;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.SummarySource;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.core.ParameterizedTypeReference;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final EmailRepository emailRepository;
    private final ImapService imapService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${GEMINI_API_KEY:}")
    private String geminiApiKeyRaw;

    // Parsed/rotating API keys (supports comma-separated keys in .env for rotation)
    private String[] geminiApiKeys = new String[0];
    private int geminiKeyIndex = 0;

    @Value("${gemini.chat-model:gemini-2.5-flash}")
    private String geminiModel;

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private String getGeminiUrl() {
        return GEMINI_BASE_URL + geminiModel + ":generateContent";
    }

    @Value("${LOCAL_LLM_URL:}")
    private String localLlmUrl;

    @Value("${LOCAL_LLM_ENABLED:false}")
    private boolean localLlmEnabled;

    private String callLocalModelApi(String text) {
        if (!localLlmEnabled || localLlmUrl == null || localLlmUrl.isBlank()) {
            throw new RuntimeException("Local LLM not configured/enabled");
        }

        try {
            Map<String, Object> req = new HashMap<>();
            // Generic payload: many local LLM proxies accept { "text": "..." } or { "prompt": "..." }
            req.put("text", "Summarize this email in 1-2 sentences: " + text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(req, headers);

                ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    localLlmUrl,
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
                );
                Map<String, Object> body = resp.getBody();
                if (body == null) throw new RuntimeException("Empty response from local LLM");

            // Common response shapes
            // 1) { "text": "..." }
            if (body.containsKey("text") && body.get("text") instanceof String) {
                return (String) body.get("text");
            }

            // 2) { "result": "..." } or { "output": "..." }
            if (body.containsKey("result") && body.get("result") instanceof String) {
                return (String) body.get("result");
            }
            if (body.containsKey("output") && body.get("output") instanceof String) {
                return (String) body.get("output");
            }

            // 3) OpenAI-ish: { "choices": [ { "text": "..." } ] }
            if (body.containsKey("choices")) {
                Object choicesObj = body.get("choices");
                if (choicesObj instanceof List) {
                    List<?> choices = (List<?>) choicesObj;
                    if (!choices.isEmpty() && choices.get(0) instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> first = (Map<String, Object>) choices.get(0);
                        if (first.containsKey("text") && first.get("text") instanceof String) {
                            return (String) first.get("text");
                        }
                        if (first.containsKey("message") && first.get("message") instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> msg = (Map<String, Object>) first.get("message");
                            if (msg.containsKey("content") && msg.get("content") instanceof String) {
                                return (String) msg.get("content");
                            }
                        }
                    }
                }
            }

            // 4) Ollama-like: { "result": [{"content": "..."}] }
            if (body.containsKey("result")) {
                Object resObj = body.get("result");
                if (resObj instanceof List) {
                    List<?> resList = (List<?>) resObj;
                    if (!resList.isEmpty() && resList.get(0) instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = (Map<String, Object>) resList.get(0);
                        if (entry.containsKey("content") && entry.get("content") instanceof String) {
                            return (String) entry.get("content");
                        }
                    }
                }
            }

            // Fallback: try to stringify
            return body.toString();
        } catch (Exception e) {
            log.error("Local LLM request failed: {}", e.getMessage());
            throw new RuntimeException("Local LLM call failed", e);
        }
    }

    @Transactional
    public String summarizeEmail(Long emailId) {
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));

        // Rule 1: [Gemini] -> Do nothing, return immediately
        if (email.getSummarySource() == SummarySource.GEMINI && email.getSummary() != null && !email.getSummary().isEmpty()) {
            log.info("Email already has Gemini summary. Skipping.");
            return email.getSummary();
        }

        // Rule 2 & 3: [Local Model] or [Local Algo] -> Try upgrading to Gemini
        // Fetch content if missing
        String content = (email.getBody() != null && !email.getBody().isEmpty()) 
                ? email.getBody() 
                : email.getSnippet();

        if (content == null || content.isEmpty()) {
            content = fetchBodyOnDemand(email);
        }

        if (content == null || content.isEmpty()) {
            return "No content to summarize.";
        }

        try {
            // Attempt Tier 1: Gemini
            String summary = callGeminiApi(content);
            email.setSummary("[Gemini] " + summary);
            email.setSummarySource(SummarySource.GEMINI);
            emailRepository.save(email);
            return email.getSummary();
        } catch (Exception e) {
            log.warn("Gemini upgrade failed: {}", e.getMessage());

            // Tier 2: Local Model (if enabled/configured)
            if (localLlmEnabled && localLlmUrl != null && !localLlmUrl.isBlank()) {
                try {
                    String localSummary = callLocalModelApi(content);
                    if (localSummary != null && !localSummary.isBlank()) {
                        email.setSummary("[Local Model] " + localSummary);
                        email.setSummarySource(SummarySource.LOCAL_MODEL);
                        emailRepository.save(email);
                        return email.getSummary();
                    }
                } catch (Exception le) {
                    log.warn("Local model fallback failed: {}", le.getMessage());
                    // fall through to extractive
                }
            } else {
                log.debug("Local LLM not configured or disabled, skipping local model fallback");
            }

            // If current email already has a LOCAL_MODEL summary, keep it
            if (email.getSummarySource() == SummarySource.LOCAL_MODEL && email.getSummary() != null && !email.getSummary().isEmpty()) {
                log.info("Keeping existing LOCAL_MODEL summary for email ID {}", email.getId());
                return email.getSummary();
            }

            // Tier 3: Local Algorithm (Extractive)
            if (email.getSummarySource() != SummarySource.LOCAL_ALGO) {
                String algoSummary = extractiveSummary(content, 3, 300);
                email.setSummary("[Local Algo] " + algoSummary);
                email.setSummarySource(SummarySource.LOCAL_ALGO);
                emailRepository.save(email);
            }

            return email.getSummary();
        }
    }

    /**
     * Fetches body content on-demand via IMAP when local body is empty.
     */
    private String fetchBodyOnDemand(EmailEntity email) {
        try {
            if (email.getUid() == null || email.getAccount() == null) {
                log.warn("Cannot fetch body: UID or account is null for email ID: {}", email.getId());
                return null;
            }

            // Fetch full message detail from IMAP
            MailMessageDetailDto detail = imapService.getMessageDetail(
                    email.getAccount(), 
                    "INBOX", // Default to INBOX, could be enhanced to track folder
                    email.getUid()
            );

            if (detail != null) {
                // Prefer plain text, fallback to HTML (strip tags)
                String body = detail.getBodyText();
                if (body == null || body.isEmpty()) {
                    body = detail.getBodyHtml();
                    if (body != null) {
                        // Strip HTML tags for plain text summarization
                        body = body.replaceAll("<[^>]*>", "").replaceAll("&nbsp;", " ").trim();
                    }
                }
                
                if (body != null && !body.isEmpty()) {
                    // Cache the body for future use
                    email.setBody(body);
                    emailRepository.save(email);
                    log.info("Fetched and cached body for email ID: {}", email.getId());
                    return body;
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch body on-demand for email ID: {}: {}", email.getId(), e.getMessage());
        }
        return null;
    }

    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        // Parse possible comma-separated GEMINI_API_KEY into an array for rotation
        if (geminiApiKeyRaw != null && !geminiApiKeyRaw.isBlank()) {
            String[] parts = geminiApiKeyRaw.split(",");
            List<String> keys = new ArrayList<>();
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty()) keys.add(t);
            }
            geminiApiKeys = keys.toArray(new String[0]);
        }

        log.info("AiService initialized. Gemini API Keys configured: {}", geminiApiKeys.length);
        if (geminiApiKeys.length > 0) {
            log.debug("Gemini first key length: {}", geminiApiKeys[0].length());
        }
        log.info("Local LLM enabled: {}. Local LLM URL present: {}", localLlmEnabled, (localLlmUrl != null && !localLlmUrl.isBlank()));
    }

    private synchronized String getNextGeminiApiKey() {
        if (geminiApiKeys == null || geminiApiKeys.length == 0) return null;
        String k = geminiApiKeys[geminiKeyIndex];
        geminiKeyIndex = (geminiKeyIndex + 1) % geminiApiKeys.length;
        return k;
    }

    private String callGeminiApi(String text) {
        String key = getNextGeminiApiKey();
        if (key == null || key.isEmpty()) {
            throw new RuntimeException("Gemini API Key not configured");
        }

        String url = getGeminiUrl() + "?key=" + key;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            // Construct request using Map and ObjectMapper to ensure valid JSON
            Map<String, Object> part = new HashMap<>();
            part.put("text", "Summarize this email in 1-2 sentences: " + text);
            
            Map<String, Object> content = new HashMap<>();
            content.put("parts", Collections.singletonList(part));
            
            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("contents", Collections.singletonList(content));
            
            String requestBody = objectMapper.writeValueAsString(requestBodyMap);

            log.debug("Sending request to Gemini...");
            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("candidates")) {
                Object candObj = body.get("candidates");
                if (candObj instanceof List) {
                    List<?> candidates = (List<?>) candObj;
                    if (!candidates.isEmpty() && candidates.get(0) instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> first = (Map<String, Object>) candidates.get(0);
                        Object contentObj = first.get("content");
                        if (contentObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> candContent = (Map<String, Object>) contentObj;
                            Object partsObj = candContent.get("parts");
                            if (partsObj instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> parts = (List<Map<String, Object>>) partsObj;
                                if (!parts.isEmpty() && parts.get(0).get("text") instanceof String) {
                                    return (String) parts.get(0).get("text");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Gemini API Request Failed: {}", e.getMessage());
            if (e instanceof org.springframework.web.client.HttpClientErrorException) {
                log.error("Response Body: {}", ((org.springframework.web.client.HttpClientErrorException) e).getResponseBodyAsString());
            }
            throw new RuntimeException("Gemini API call failed", e);
        }
        
        throw new RuntimeException("Failed to generate summary: Empty response");
    }

    /**
     * Fallback Algorithm
     */
    public String extractiveSummary(String text, int topSentences, int maxChars) {
        if (text == null) return "";
        
        // Strip HTML tags before summarization to avoid tags in summary and prevent script execution
        String cleanText = text.replaceAll("<[^>]*>", " ");
        cleanText = cleanText.replaceAll("&nbsp;", " ");
        cleanText = cleanText.replaceAll("\\s+", " ").trim();
        
        if (cleanText.isEmpty()) {
            return "";
        }

        // Split into sentences (Simplified regex)
        String[] matchesArr = cleanText.split("(?<=[.!?])\\s+");
        List<String> matches = Arrays.asList(matchesArr);

        if (matches.isEmpty()) {
            return text.length() > maxChars ? text.substring(0, maxChars) : text;
        }

        // Build frequency map
        Map<String, Double> freq = new HashMap<>();
        int totalWords = 0;
        Pattern wordRE = Pattern.compile("[A-Za-z0-9']+");

        for (String s : matches) {
            Matcher m = wordRE.matcher(s.toLowerCase());
            while (m.find()) {
                String w = m.group();
                if (w.length() <= 2) continue;
                freq.put(w, freq.getOrDefault(w, 0.0) + 1.0);
                totalWords++;
            }
        }

        if (totalWords == 0) {
            String out = String.join(" ", matches);
            return out.length() > maxChars ? out.substring(0, maxChars) : out;
        }

        // Score sentences
        List<SentenceScore> scores = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            String s = matches.get(i);
            double sc = 0.0;
            Matcher m = wordRE.matcher(s.toLowerCase());
            int wordCount = 0;
            while (m.find()) {
                String w = m.group();
                if (freq.containsKey(w)) {
                    sc += freq.get(w);
                }
                wordCount++;
            }
            if (wordCount > 0) {
                sc = sc / wordCount;
            }
            scores.add(new SentenceScore(i, sc, s.trim()));
        }

        // Pick top sentences
        scores.sort((a, b) -> Double.compare(b.score, a.score));

        int limit = Math.min(topSentences, scores.size());
        List<SentenceScore> chosen = new ArrayList<>(scores.subList(0, limit));

        // Restore original order
        chosen.sort(Comparator.comparingInt(a -> a.idx));

        StringBuilder result = new StringBuilder();
        int outLen = 0;
        for (SentenceScore c : chosen) {
            if (outLen + c.text.length() > maxChars && outLen > 0) {
                break;
            }
            if (result.length() > 0) result.append(" ");
            result.append(c.text);
            outLen += c.text.length();
        }

        String res = result.toString();
        if (res.length() > maxChars) {
            res = res.substring(0, maxChars) + "...";
        }
        return res.trim();
    }

    public String suggestSearchQuery(String input, Long userId) {
        if (geminiApiKeys == null || geminiApiKeys.length == 0) {
            return "from: " + input;
        }

        String prompt = "Give a 3-5 word email search query for: " + input + ". Respond with ONLY the query.";
        try {
            return callGeminiApi(prompt);
        } catch (Exception e) {
            return "from: " + input;
        }
    }

    private static class SentenceScore {
        int idx;
        double score;
        String text;

        public SentenceScore(int idx, double score, String text) {
            this.idx = idx;
            this.score = score;
            this.text = text;
        }
    }
}
