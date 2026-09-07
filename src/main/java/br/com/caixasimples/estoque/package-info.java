/**
 * Bounded Context de estoque: entrada, saida e ajuste manual, com alerta de estoque baixo.
 *
 * <p>Modulo opcional — desligavel por conta via {@code Conta.estoqueHabilitado} (RF17), porque
 * negocio baseado em servico (salao, oficina) nao precisa dele. Reage a eventos de venda em vez de
 * ser chamado por {@code vendas}.
 */
@ApplicationModule(displayName = "Estoque")
package br.com.caixasimples.estoque;

import org.springframework.modulith.ApplicationModule;
