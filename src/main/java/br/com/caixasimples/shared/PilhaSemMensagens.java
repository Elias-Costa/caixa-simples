package br.com.caixasimples.shared;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A pilha de uma exceção sem nenhuma mensagem: a classe de cada exceção da cadeia de causas e as
 * linhas por onde ela passou.
 *
 * <p>A mensagem de uma exceção pode trazer dado pessoal, como a violação de unicidade que o banco
 * devolve com o valor repetido, e o log não pode guardá-lo. A classe e as linhas dizem onde o erro
 * aconteceu, que é o que o diagnóstico precisa primeiro, e o texto de cada linha só tem classe,
 * método, arquivo e número.
 *
 * <p>Como na pilha que o próprio Java escreve, cada causa omite as linhas finais que tem em comum
 * com a exceção de cima. As exceções suprimidas ficam de fora: raramente explicam o erro, e cada uma
 * traria a própria cadeia. Uma causa que aponta de volta para a cadeia encerra o texto, em vez de
 * repeti-lo para sempre.
 */
public final class PilhaSemMensagens {

    private static final String QUEBRA = System.lineSeparator();

    private PilhaSemMensagens() {
    }

    public static String de(Throwable excecao) {
        StringBuilder texto = new StringBuilder();
        Set<Throwable> vistas = Collections.newSetFromMap(new IdentityHashMap<>());
        StackTraceElement[] deCima = new StackTraceElement[0];

        Throwable atual = excecao;
        while (atual != null && vistas.add(atual)) {
            if (atual != excecao) {
                texto.append(QUEBRA).append("Caused by: ");
            }
            texto.append(atual.getClass().getName());

            StackTraceElement[] linhas = atual.getStackTrace();
            int emComum = linhasEmComum(linhas, deCima);
            for (int i = 0; i < linhas.length - emComum; i++) {
                texto.append(QUEBRA).append("\tat ").append(linhas[i]);
            }
            if (emComum > 0) {
                texto.append(QUEBRA).append("\t... ").append(emComum).append(" more");
            }

            deCima = linhas;
            atual = atual.getCause();
        }
        if (atual != null) {
            texto.append(QUEBRA).append("Caused by: ciclo na cadeia de causas, em ")
                    .append(atual.getClass().getName());
        }
        return texto.toString();
    }

    /** Quantas linhas do fim das duas pilhas são iguais, comparando de trás para a frente. */
    private static int linhasEmComum(StackTraceElement[] linhas, StackTraceElement[] deCima) {
        int emComum = 0;
        int i = linhas.length - 1;
        int j = deCima.length - 1;
        while (i >= 0 && j >= 0 && linhas[i].equals(deCima[j])) {
            emComum++;
            i--;
            j--;
        }
        return emComum;
    }
}
