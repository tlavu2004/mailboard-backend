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
    private String geminiApiKey;

    @Value("${gemini.chat-model:gemini-2.5-flash}")
    private String geminiModel;

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private String getGeminiUrl() {
        return GEMINI_BASE_URL + geminiModel + ":generateContent";
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
            log.warn("Gemini upgrade failed, checking for local model fallback: {}", e.getMessage());
            
            // Tier 2: Local Model (Placeholder for Ollama/Local LLM)
            // Currently we don't have a local LLM, so we fallback to Local Algo if current is ALGO or null.
            // If current is already LOCAL_MODEL, we keep it.
            
            if (email.getSummarySource() == SummarySource.LOCAL_MODEL && email.getSummary() != null) {
                return email.getSummary();
            }

            // Tier 3: Local Algorithm (Extractive)
            if (email.getSummarySource() != SummarySource.LOCAL_ALGO) {
                String summary = extractiveSummary(content, 3, 300);
                email.setSummary("[Local Algo] " + summary);
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
        log.info("AiService initialized. Gemini API Key Present: {}", 
            (geminiApiKey != null && !geminiApiKey.isEmpty()));
        if (geminiApiKey != null && !geminiApiKey.isEmpty()) {
             log.debug("Gemini Key Length: {}", geminiApiKey.length());
        }
    }

    @SuppressWarnings("unchecked")
    private String callGeminiApi(String text) {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            throw new RuntimeException("Gemini API Key not configured");
        }

        String url = getGeminiUrl() + "?key=" + geminiApiKey;
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
            
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            if (body != null && body.containsKey("candidates")) {
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidates");
                if (!candidates.isEmpty()) {
                    Map<String, Object> candContent = (Map<String, Object>) candidates.get(0).get("content");
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) candContent.get("parts");
                    if (!parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
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
        text = text.trim();
        if (text.isEmpty()) {
            return "";
        }

        // Split into sentences (Simplified regex)
        String[] matchesArr = text.split("(?<=[.!?])\\s+");
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
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
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
