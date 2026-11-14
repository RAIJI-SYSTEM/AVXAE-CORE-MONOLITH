package com.raiji.users.driververification;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Regras da verificação da CNH (sem rede, sem banco: fácil de testar).
 *
 * <p>Códigos da Infosimples: 200 = sucesso. Entre os 6xx, os que indicam problema
 * nos DADOS (cobrados) viram REJECTED; os técnicos/transitórios (não cobrados)
 * viram ERROR — o motorista tenta de novo, sem nova tentativa automática
 * (a própria Infosimples recomenda não repetir em loop, pela cobrança).
 */
public final class CnhDecision {

    public enum Outcome { APPROVED, REJECTED, ERROR }

    /** Dados lidos da foto que alimentam a validação */
    public record OcrData(String nome, String cpf, String registro) {}

    /** Resultado do OCR: os dados, ou a decisão final se não deu para ler */
    public record OcrOutcome(OcrData data, Result failure) {
        public boolean ok() {
            return data != null;
        }
    }

    public record Result(Outcome outcome, String reason, String categoria, LocalDate validade, String cnhName) {
        static Result rejected(String reason) {
            return new Result(Outcome.REJECTED, reason, null, null, null);
        }

        static Result error(String reason) {
            return new Result(Outcome.ERROR, reason, null, null, null);
        }
    }

    /** Problemas nos dados (cobrados pela Infosimples): não adianta repetir igual */
    static final Set<Integer> DATA_PROBLEM = Set.of(606, 607, 608, 611, 612, 619, 620);
    static final double MIN_OCR_SCORE = 0.5;
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Set<String> NAME_CONNECTORS = Set.of("DE", "DA", "DO", "DAS", "DOS", "E");

    static final String UNAVAILABLE = "A verificação está indisponível no momento. Tente novamente mais tarde.";
    static final String UNREADABLE = "Não conseguimos ler a CNH na foto. Envie uma foto nítida, sem reflexo, "
            + "mostrando a frente inteira do documento.";

    private CnhDecision() {
    }

    // ------------------------------------------------------------------ OCR

    @SuppressWarnings("unchecked")
    public static OcrOutcome readOcr(InfosimplesClient.Response ocr) {
        if (ocr.code() != 200) {
            return new OcrOutcome(null, DATA_PROBLEM.contains(ocr.code())
                    ? Result.rejected(UNREADABLE)
                    : Result.error(UNAVAILABLE));
        }
        Map<String, Object> first = ocr.first();
        Object tipo = first.get("tipo");
        if (tipo != null && !String.valueOf(tipo).toLowerCase().startsWith("cnh")) {
            return new OcrOutcome(null, Result.rejected("A imagem enviada não parece ser uma CNH."));
        }
        Map<String, Object> campos = first.get("campos") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        String nome = field(campos, "nome");
        String cpf = digits(field(campos, "cpf"));
        String registro = digits(field(campos, "registro"));
        if (nome == null || cpf == null || cpf.length() != 11 || registro == null || registro.length() < 9) {
            return new OcrOutcome(null, Result.rejected(UNREADABLE));
        }
        return new OcrOutcome(new OcrData(nome.trim(), cpf, registro), null);
    }

    @SuppressWarnings("unchecked")
    private static String field(Map<String, Object> campos, String name) {
        if (!(campos.get(name) instanceof Map<?, ?> raw)) return null;
        Map<String, Object> f = (Map<String, Object>) raw;
        Object valor = f.get("valor");
        if (valor == null || String.valueOf(valor).isBlank()) return null;
        if (f.get("score") instanceof Number score && score.doubleValue() < MIN_OCR_SCORE) return null;
        return String.valueOf(valor);
    }

    // ------------------------------------------------------------ Validação

    /**
     * @param accountName nome da conta (Keycloak) — precisa estar contido no nome da CNH
     */
    public static Result decide(InfosimplesClient.Response validation, OcrData ocr, String accountName, LocalDate today) {
        if (validation.code() == 612) {
            return Result.rejected("Não encontramos esta CNH na base do SENATRAN. Confira o código de segurança.");
        }
        if (DATA_PROBLEM.contains(validation.code())) {
            return Result.rejected("A base oficial recusou os dados da CNH. Confira o código de segurança "
                    + "e envie uma foto nítida.");
        }
        if (validation.code() != 200) {
            return Result.error(UNAVAILABLE);
        }

        Map<String, Object> cnh = validation.first();
        String situacao = text(cnh.get("situacao"));
        String categoria = text(cnh.get("categoria"));
        String cnhName = text(cnh.get("nome")) != null ? text(cnh.get("nome")) : ocr.nome();
        LocalDate validade = parseDate(text(cnh.get("validade_data")));

        if (situacao == null || !"VALIDA".equals(normalize(situacao))) {
            return Result.rejected("Sua CNH está com a situação \"" + (situacao == null ? "desconhecida" : situacao)
                    + "\" na base do SENATRAN.");
        }
        if (validade == null) {
            return Result.rejected("Não foi possível confirmar a validade da CNH.");
        }
        if (validade.isBefore(today)) {
            return Result.rejected("Sua CNH venceu em " + validade.format(BR_DATE) + ".");
        }
        if (categoria == null) {
            return Result.rejected("Não foi possível confirmar a categoria da CNH.");
        }
        if (!sameName(accountName, cnhName)) {
            return Result.rejected("O nome na CNH não corresponde ao nome da sua conta. "
                    + "Atualize o nome da conta para o mesmo da CNH.");
        }
        return new Result(Outcome.APPROVED, null, categoria.trim().toUpperCase(), validade, cnhName);
    }

    /** Todas as palavras do nome da conta aparecem no nome da CNH (sem acento/maiúsculas; ignora "de", "da"...) */
    static boolean sameName(String accountName, String cnhName) {
        Set<String> account = nameTokens(accountName);
        if (account.isEmpty() || cnhName == null) return false;
        return nameTokens(cnhName).containsAll(account);
    }

    private static Set<String> nameTokens(String name) {
        if (name == null) return Set.of();
        return Arrays.stream(normalize(name).split(" "))
                .filter(t -> t.length() > 1 && !NAME_CONNECTORS.contains(t))
                .collect(Collectors.toSet());
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase()
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static String digits(String value) {
        if (value == null) return null;
        String d = value.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value, BR_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
