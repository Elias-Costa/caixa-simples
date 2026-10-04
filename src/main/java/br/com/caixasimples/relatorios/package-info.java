/**
 * Bounded Context de relatórios: faturamento, itens mais vendidos e fluxo de caixa, com os
 * filtros por período, forma de pagamento e operador.
 *
 * <p><strong>Somente leitura.</strong> Este módulo nunca escreve em outro módulo, e a restrição é
 * verificada por {@code ModularityTests}.
 *
 * <p>Todo relatório é do administrador e de um plano pago: o módulo pergunta ao de contas se o
 * plano inclui relatórios e se não está suspenso, sem conhecer preço nem vencimento.
 */
@ApplicationModule(displayName = "Relatorios")
package br.com.caixasimples.relatorios;

import org.springframework.modulith.ApplicationModule;
