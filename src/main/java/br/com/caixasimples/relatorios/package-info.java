/**
 * Bounded Context de relatorios: faturamento, itens mais vendidos e fluxo de caixa.
 *
 * <p><strong>Somente leitura.</strong> Este modulo nunca escreve em outro modulo — restricao
 * verificada por {@code ModularityTests}.
 */
@ApplicationModule(displayName = "Relatorios")
package br.com.caixasimples.relatorios;

import org.springframework.modulith.ApplicationModule;
