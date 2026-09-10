/**
 * Bounded Context de estoque: entrada, saída e ajuste manual, com alerta de estoque baixo.
 *
 * <p>Módulo opcional, desligável por conta via {@code Conta.estoqueHabilitado} (RF17), porque
 * negócio baseado em serviço não precisa dele. Reage a eventos de venda em vez de ser chamado por
 * {@code vendas}.
 */
@ApplicationModule(displayName = "Estoque")
package br.com.caixasimples.estoque;

import org.springframework.modulith.ApplicationModule;
