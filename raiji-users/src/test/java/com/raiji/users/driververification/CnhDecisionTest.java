package com.raiji.users.driververification;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.raiji.users.driververification.CnhDecision.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CnhDecisionTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 10);
    private static final CnhDecision.OcrData OCR = new CnhDecision.OcrData("MARIA DA SILVA SOUZA", "12345678901", "98765432100");

    private static InfosimplesClient.Response resposta(int code, Map<String, Object> data) {
        return new InfosimplesClient.Response(code, "msg", data == null ? List.of() : List.of(data), List.of(), true);
    }

    private static Map<String, Object> cnh(String situacao, String validade, String categoria, String nome) {
        Map<String, Object> m = new HashMap<>();
        m.put("situacao", situacao);
        m.put("validade_data", validade);
        m.put("categoria", categoria);
        m.put("nome", nome);
        return m;
    }

    private static Map<String, Object> campo(Object valor, double score) {
        return Map.of("valor", valor, "score", score);
    }

    private static InfosimplesClient.Response ocr(String tipo, Map<String, Object> campos) {
        return resposta(200, Map.of("tipo", tipo, "campos", campos));
    }

    // ------------------------------------------------------------------ OCR

    @Test
    @DisplayName("OCR legível: extrai nome, CPF e registro (só dígitos)")
    void ocrOk() {
        CnhDecision.OcrOutcome r = CnhDecision.readOcr(ocr("cnh_frente", Map.of(
                "nome", campo("MARIA DA SILVA SOUZA", 0.9),
                "cpf", campo("123.456.789-01", 0.9),
                "registro", campo("98765432100", 0.9))));

        assertThat(r.ok()).isTrue();
        assertThat(r.data()).isEqualTo(OCR);
    }

    @Test
    @DisplayName("OCR com baixa confiança no CPF: rejeita pedindo foto nítida")
    void ocrBaixaConfianca() {
        CnhDecision.OcrOutcome r = CnhDecision.readOcr(ocr("cnh_frente", Map.of(
                "nome", campo("MARIA", 0.9),
                "cpf", campo("123.456.789-01", 0.2),
                "registro", campo("98765432100", 0.9))));

        assertThat(r.ok()).isFalse();
        assertThat(r.failure().outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.failure().reason()).contains("foto nítida");
    }

    @Test
    @DisplayName("imagem que não é CNH (ex.: RG): rejeita")
    void ocrNaoEhCnh() {
        CnhDecision.OcrOutcome r = CnhDecision.readOcr(ocr("rg_frente", Map.of()));

        assertThat(r.failure().outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.failure().reason()).contains("não parece ser uma CNH");
    }

    @Test
    @DisplayName("OCR: erro de dados (cobrado) rejeita; falha técnica (não cobrada) vira ERROR")
    void ocrCodigos() {
        assertThat(CnhDecision.readOcr(resposta(612, null)).failure().outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(CnhDecision.readOcr(resposta(615, null)).failure().outcome()).isEqualTo(Outcome.ERROR);
        assertThat(CnhDecision.readOcr(resposta(601, null)).failure().outcome()).isEqualTo(Outcome.ERROR);
    }

    // ------------------------------------------------------------ Validação

    @Test
    @DisplayName("CNH válida, no prazo, categoria e nome conferem: APROVA")
    void aprova() {
        CnhDecision.Result r = CnhDecision.decide(
                resposta(200, cnh("válida", "10/11/2029", "ab", "MARIA DA SILVA SOUZA")), OCR, "Maria Souza", HOJE);

        assertThat(r.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(r.categoria()).isEqualTo("AB");
        assertThat(r.validade()).isEqualTo(LocalDate.of(2029, 11, 10));
    }

    @Test
    @DisplayName("vence hoje ainda vale; venceu ontem rejeita")
    void validade() {
        assertThat(CnhDecision.decide(resposta(200, cnh("válida", "10/11/2026", "B", "MARIA DA SILVA SOUZA")),
                OCR, "Maria Souza", HOJE).outcome()).isEqualTo(Outcome.APPROVED);

        CnhDecision.Result vencida = CnhDecision.decide(resposta(200, cnh("válida", "09/11/2026", "B", "MARIA DA SILVA SOUZA")),
                OCR, "Maria Souza", HOJE);
        assertThat(vencida.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(vencida.reason()).contains("venceu em 09/11/2026");
    }

    @Test
    @DisplayName("situação diferente de válida (ex.: suspensa): rejeita mostrando a situação")
    void suspensa() {
        CnhDecision.Result r = CnhDecision.decide(
                resposta(200, cnh("suspensa", "10/11/2029", "B", "MARIA DA SILVA SOUZA")), OCR, "Maria Souza", HOJE);

        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.reason()).contains("suspensa");
    }

    @Test
    @DisplayName("nome da CNH diferente do nome da conta: rejeita")
    void nomeDiferente() {
        CnhDecision.Result r = CnhDecision.decide(
                resposta(200, cnh("válida", "10/11/2029", "B", "JOAO PEREIRA")), OCR, "Maria Souza", HOJE);

        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.reason()).contains("nome");
    }

    @Test
    @DisplayName("comparação de nome ignora acento, maiúsculas e 'de/da/dos'")
    void comparacaoDeNome() {
        assertThat(CnhDecision.sameName("José Antônio", "JOSE ANTONIO DOS SANTOS")).isTrue();
        assertThat(CnhDecision.sameName("Pablo Murilo", "PABLO MURILO SANTOS CAETANO")).isTrue();
        assertThat(CnhDecision.sameName("Maria da Silva", "MARIA SILVA")).isTrue();
        assertThat(CnhDecision.sameName("Maria Oliveira", "MARIA SILVA")).isFalse();
        assertThat(CnhDecision.sameName("", "MARIA SILVA")).isFalse();
    }

    @Test
    @DisplayName("SENATRAN não encontrou (612) ou recusou os dados: rejeita; indisponível: ERROR")
    void codigosValidacao() {
        CnhDecision.Result naoEncontrada = CnhDecision.decide(resposta(612, null), OCR, "Maria", HOJE);
        assertThat(naoEncontrada.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(naoEncontrada.reason()).contains("código de segurança");

        assertThat(CnhDecision.decide(resposta(608, null), OCR, "Maria", HOJE).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(CnhDecision.decide(resposta(605, null), OCR, "Maria", HOJE).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(CnhDecision.decide(resposta(618, null), OCR, "Maria", HOJE).outcome()).isEqualTo(Outcome.ERROR);
    }
}
