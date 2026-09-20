/**
 * Bounded Context de estoque: a baixa por venda, o estorno por cancelamento, o ajuste manual, o
 * estoque mínimo e o alerta de estoque baixo.
 *
 * <p>Módulo opcional, desligável por conta via {@code Conta.estoqueHabilitado} (RF17), porque
 * negócio baseado em serviço não precisa dele. Reage aos eventos de venda concluída e de venda
 * cancelada em vez de ser chamado por {@code vendas}, e recusa os casos de uso acionados por
 * pessoa quando a conta não ligou o controle.
 *
 * <p><strong>Este módulo decide; o cadastro executa.</strong> O agregado Produto, com o saldo, o
 * mínimo e os movimentos de estoque, é do módulo de cadastro, e a entidade dele não se abre de
 * fora. O que mora aqui é a política: quando uma venda concluída vira baixa e uma cancelada vira
 * estorno, e para quais contas; quais contas podem ajustar saldo, definir mínimo e consultar o
 * alerta. Os ouvintes em {@code internal} e o serviço em {@code application} perguntam à conta se
 * o controle está ligado e pedem ao cadastro pela API pública dele. Grafo resultante: estoque
 * depende de vendas, cadastro e contas; nenhum dos três depende de estoque.
 */
@ApplicationModule(displayName = "Estoque")
package br.com.caixasimples.estoque;

import org.springframework.modulith.ApplicationModule;
