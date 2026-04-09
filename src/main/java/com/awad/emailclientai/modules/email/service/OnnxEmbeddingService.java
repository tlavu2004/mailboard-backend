package com.awad.emailclientai.modules.email.service;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.*;

@Service
public class OnnxEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(OnnxEmbeddingService.class);
    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private boolean available = false;

    @PostConstruct
    public void init() {
        try {
            env = OrtEnvironment.getEnvironment();

            // Load ONNX model
            byte[] modelBytes = new ClassPathResource("models/all-MiniLM-L6-v2.onnx").getContentAsByteArray();
            session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            logger.info("Local ONNX model loaded successfully.");

            // Load HuggingFace tokenizer
            InputStream tokenizerStream = new ClassPathResource("models/tokenizer.json").getInputStream();
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerStream, Map.of(
                "maxLength", "256",
                "padding", "true",
                "truncation", "true"
            ));
            logger.info("HuggingFace tokenizer loaded successfully.");
            available = true;

        } catch (Exception e) {
            logger.warn("Failed to load local ONNX model or tokenizer. Local fallback will not work.", e);
            available = false;
        }
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        if (session == null || tokenizer == null) {
            throw new RuntimeException("ONNX session or tokenizer not initialized");
        }

        try {
            // 1. Tokenize with BERT WordPiece tokenizer
            Encoding encoding = tokenizer.encode(text);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] tokenTypeIds = encoding.getTypeIds();
            int seqLength = inputIds.length;

            // 2. Create ONNX tensors
            long[] shape = new long[]{1, seqLength};

            OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(inputIds), shape);
            OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(attentionMask), shape);
            OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(tokenTypeIds), shape);

            Map<String, OnnxTensor> inputs = Map.of(
                    "input_ids", inputIdsTensor,
                    "attention_mask", attentionMaskTensor,
                    "token_type_ids", tokenTypeIdsTensor
            );

            // 3. Run inference
            try (OrtSession.Result result = session.run(inputs)) {
                // Output shape: [1, seqLength, 384] (token embeddings)
                float[][][] tokenEmbeddings = (float[][][]) result.get(0).getValue();

                // 4. Mean pooling with attention mask
                float[] sentenceEmbedding = meanPooling(tokenEmbeddings[0], attentionMask);

                // 5. Convert to List<Float>
                List<Float> embeddingList = new ArrayList<>(sentenceEmbedding.length);
                for (float v : sentenceEmbedding) {
                    embeddingList.add(v);
                }

                logger.debug("Generated local ONNX embedding with {} dimensions", embeddingList.size());
                return embeddingList;

            } finally {
                inputIdsTensor.close();
                attentionMaskTensor.close();
                tokenTypeIdsTensor.close();
            }

        } catch (Exception e) {
            logger.error("Error generating local embedding", e);
            throw new RuntimeException("Local ONNX embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Mean pooling: average token embeddings weighted by attention mask.
     * Tokens with attention_mask=0 (padding) are excluded from the average.
     */
    private float[] meanPooling(float[][] tokenEmbeddings, long[] attentionMask) {
        int hiddenSize = tokenEmbeddings[0].length; // 384
        float[] sumEmbedding = new float[hiddenSize];
        float maskSum = 0;

        for (int i = 0; i < tokenEmbeddings.length; i++) {
            float mask = attentionMask[i];
            maskSum += mask;
            for (int j = 0; j < hiddenSize; j++) {
                sumEmbedding[j] += tokenEmbeddings[i][j] * mask;
            }
        }

        // Avoid division by zero
        if (maskSum == 0) maskSum = 1;

        for (int j = 0; j < hiddenSize; j++) {
            sumEmbedding[j] /= maskSum;
        }

        return sumEmbedding;
    }

    /**
     * Check if the ONNX model and tokenizer are loaded and ready.
     */
    public boolean isAvailable() {
        return available;
    }

    @Override
    public int getPreferredDimension() {
        return 384;
    }

    @PreDestroy
    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
