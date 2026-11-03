package br.com.caixasimples.relatorios.application;

/**
 * Como o ranking dos mais vendidos se ordena: pelo que mais saiu ou pelo que mais rendeu.
 *
 * <p>São duas perguntas diferentes, e as duas valem. A quantidade é o mais vendido do balcão, o café
 * que saiu cem vezes; mas soma unidade, fatia e quilo na mesma régua, e o que se vende a peso, alguns
 * gramas por vez, nunca entra entre os primeiros. O valor põe todos na mesma moeda, e é por ele que a
 * tela pergunta.
 */
public enum OrdemDoRanking {
    QUANTIDADE, VALOR
}
