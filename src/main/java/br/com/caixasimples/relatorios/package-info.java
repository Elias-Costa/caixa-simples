/**
 * Bounded Context de relatórios: faturamento, itens mais vendidos e fluxo de caixa, com os
 * filtros por período, forma de pagamento e operador.
 *
 * <p><strong>Somente leitura.</strong> Este módulo nunca escreve em outro módulo, e a restrição é
 * verificada por {@code ModularityTests}.
 */
@ApplicationModule(displayName = "Relatorios")
package br.com.caixasimples.relatorios;

import org.springframework.modulith.ApplicationModule;
