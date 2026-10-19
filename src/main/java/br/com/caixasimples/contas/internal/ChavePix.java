package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.LimiteDeTexto;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A chave Pix em que a Conta recebe o Pix lançado à mão, já conferida e na forma em que fica gravada.
 *
 * <p>O administrador escolhe o tipo, e é o tipo que decide a conferência: um CPF e um celular têm os
 * dois onze dígitos, e só o tipo diz qual dos dois foi digitado. Cada tipo confere o formato, e o CPF
 * e o CNPJ também os dígitos verificadores, porque uma chave digitada errada não desvia dinheiro, mas
 * trava o Pix no balcão até alguém perceber. O CNPJ aceita letras nas doze primeiras posições, como o
 * alfanumérico que a Receita Federal emite.
 *
 * <p>A forma gravada é a que o QR leva: o CPF com os onze dígitos, o CNPJ com os catorze caracteres
 * em maiúsculas, o celular com o código do país e os onze dígitos, o e-mail e a chave aleatória em
 * minúsculas. As formas não se confundem, então o tipo se lê de volta da chave gravada, sem coluna
 * própria.
 *
 * <p>A recusa diz o campo e o motivo, e nunca a chave: ela pode ser o CPF ou o celular do dono.
 */
public final class ChavePix {

    /** O maior e-mail aceito como chave Pix, que é também o tamanho da coluna. */
    public static final int TAMANHO_MAXIMO = 77;

    /** Os tipos de chave que o Pix aceita. */
    public enum Tipo {
        CPF,
        CNPJ,
        TELEFONE,
        EMAIL,
        ALEATORIA
    }

    /** O que a digitação traz entre os dígitos: pontos, barras, traços, parênteses e espaços. */
    private static final Pattern SEPARADORES = Pattern.compile("[\\s./()-]");
    private static final Pattern ONZE_DIGITOS = Pattern.compile("[0-9]{11}");
    private static final Pattern CNPJ = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");
    /** O DDD não tem zero, e o número do celular tem nove dígitos começando por nove. */
    private static final Pattern CELULAR = Pattern.compile("[1-9]{2}9[0-9]{8}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern ALEATORIA = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Tipo tipo;
    private final String valor;

    private ChavePix(Tipo tipo, String valor) {
        this.tipo = tipo;
        this.valor = valor;
    }

    /**
     * Confere a chave digitada pelo tipo escolhido e a devolve na forma gravada.
     *
     * @throws IllegalArgumentException se falta o tipo ou a chave, ou se a chave não serve ao tipo
     */
    public static ChavePix conferir(Tipo tipo, String digitada) {
        if (tipo == null) {
            throw new IllegalArgumentException("chave Pix: informe o tipo da chave.");
        }
        if (digitada == null || digitada.isBlank()) {
            throw new IllegalArgumentException("chave Pix: informe a chave.");
        }
        String texto = digitada.trim();
        return switch (tipo) {
            case CPF -> new ChavePix(tipo, cpf(texto));
            case CNPJ -> new ChavePix(tipo, cnpj(texto));
            case TELEFONE -> new ChavePix(tipo, celular(texto));
            case EMAIL -> new ChavePix(tipo, email(texto));
            case ALEATORIA -> new ChavePix(tipo, aleatoria(texto));
        };
    }

    /**
     * O tipo de uma chave gravada pela aplicação, que só grava a forma conferida aqui.
     *
     * @throws IllegalStateException se a chave gravada não tem nenhuma das formas, o que só uma
     *         escrita por fora da aplicação produz
     */
    public static Tipo tipoDaGravada(String gravada) {
        Objects.requireNonNull(gravada, "chave Pix gravada nao pode ser nula");
        if (gravada.startsWith("+")) {
            return Tipo.TELEFONE;
        }
        if (gravada.contains("@")) {
            return Tipo.EMAIL;
        }
        if (ALEATORIA.matcher(gravada).matches()) {
            return Tipo.ALEATORIA;
        }
        if (CNPJ.matcher(gravada).matches()) {
            return Tipo.CNPJ;
        }
        if (ONZE_DIGITOS.matcher(gravada).matches()) {
            return Tipo.CPF;
        }
        throw new IllegalStateException("chave Pix gravada fora das formas conhecidas");
    }

    public Tipo tipo() {
        return tipo;
    }

    /** A chave na forma gravada, que é a que o QR leva. */
    public String valor() {
        return valor;
    }

    private static String cpf(String texto) {
        String digitos = SEPARADORES.matcher(texto).replaceAll("");
        if (!ONZE_DIGITOS.matcher(digitos).matches()) {
            throw new IllegalArgumentException("chave Pix: o CPF tem 11 dígitos.");
        }
        if (todosIguais(digitos) || !digitosVerificadoresConferem(digitos, 9)) {
            throw new IllegalArgumentException(
                    "chave Pix: o CPF não confere com os dígitos verificadores.");
        }
        return digitos;
    }

    private static String cnpj(String texto) {
        String caracteres = SEPARADORES.matcher(texto).replaceAll("").toUpperCase(Locale.ROOT);
        if (!CNPJ.matcher(caracteres).matches()) {
            throw new IllegalArgumentException(
                    "chave Pix: o CNPJ tem 14 caracteres, 12 letras ou números e 2 dígitos.");
        }
        if (todosIguais(caracteres) || !digitosVerificadoresConferem(caracteres, 12)) {
            throw new IllegalArgumentException(
                    "chave Pix: o CNPJ não confere com os dígitos verificadores.");
        }
        return caracteres;
    }

    /**
     * O celular aceita o número com DDD ou com o código do país, com ou sem o sinal de mais. Treze
     * dígitos começando por 55 são o código do país e o número: o DDD 55 também existe, mas com ele o
     * número nacional tem onze dígitos, e não treze.
     */
    private static String celular(String texto) {
        String limpo = SEPARADORES.matcher(texto).replaceAll("");
        String nacional;
        if (limpo.startsWith("+")) {
            if (!limpo.startsWith("+55")) {
                throw new IllegalArgumentException(
                        "chave Pix: o celular da chave é um número do Brasil, com o código 55.");
            }
            nacional = limpo.substring(3);
        } else if (limpo.length() == 13 && limpo.startsWith("55")) {
            nacional = limpo.substring(2);
        } else {
            nacional = limpo;
        }
        if (!CELULAR.matcher(nacional).matches()) {
            throw new IllegalArgumentException("chave Pix: o celular tem o DDD e os 9 dígitos do"
                    + " número, começando por 9.");
        }
        return "+55" + nacional;
    }

    private static String email(String texto) {
        String minusculo = texto.toLowerCase(Locale.ROOT);
        LimiteDeTexto.exigirAte(minusculo, TAMANHO_MAXIMO, "chave Pix: o e-mail");
        if (!EMAIL.matcher(minusculo).matches()) {
            throw new IllegalArgumentException(
                    "chave Pix: o e-mail tem um nome, a arroba e o domínio, sem espaços.");
        }
        return minusculo;
    }

    private static String aleatoria(String texto) {
        String minusculo = texto.toLowerCase(Locale.ROOT);
        if (!ALEATORIA.matcher(minusculo).matches()) {
            throw new IllegalArgumentException("chave Pix: a chave aleatória tem 32 letras e"
                    + " números, em cinco grupos separados por traço, como o banco a mostra.");
        }
        return minusculo;
    }

    private static boolean todosIguais(String texto) {
        return texto.chars().allMatch(caractere -> caractere == texto.charAt(0));
    }

    /**
     * Confere os dois dígitos verificadores do CPF e do CNPJ, que seguem a mesma conta: os pesos
     * crescem de 2 em diante, da direita para a esquerda, voltando a 2 depois de 9 no CNPJ, e o resto
     * da soma por 11 decide o dígito. Cada caractere vale o código dele menos o do zero, o que dá aos
     * algarismos o próprio valor e às letras do CNPJ alfanumérico os valores de 17 a 42.
     *
     * @param base quantos caracteres vêm antes dos dígitos verificadores: 9 no CPF e 12 no CNPJ
     */
    private static boolean digitosVerificadoresConferem(String texto, int base) {
        int pesoMaximo = base == 9 ? 11 : 9;
        for (int posicao = base; posicao < base + 2; posicao++) {
            int soma = 0;
            int peso = 2;
            for (int i = posicao - 1; i >= 0; i--) {
                soma += (texto.charAt(i) - '0') * peso;
                peso = peso == pesoMaximo ? 2 : peso + 1;
            }
            int resto = soma % 11;
            int esperado = resto < 2 ? 0 : 11 - resto;
            if (texto.charAt(posicao) - '0' != esperado) {
                return false;
            }
        }
        return true;
    }
}
