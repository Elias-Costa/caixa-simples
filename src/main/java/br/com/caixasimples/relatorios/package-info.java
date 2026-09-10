/**
 * Bounded Context de relatórios: faturamento, itens mais vendidos e fluxo de caixa.
 *
 * <p><strong>Somente leitura.</strong> Este módulo nunca escreve em outro módulo, e a restrição é
 * verificada por {@code ModularityTests}.
 */
@ApplicationModule(displayName = "Relatorios")
package br.com.caixasimples.relatorios;

import org.springframework.modulith.ApplicationModule;
