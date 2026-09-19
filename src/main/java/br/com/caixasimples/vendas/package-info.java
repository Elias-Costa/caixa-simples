/**
 * Bounded Context de vendas: agregado {@code Venda}, raiz que tem {@code ItemVenda} e
 * {@code Pagamento} como membros.
 *
 * <p>Concluir uma venda publica {@code VendaConcluida}, o evento de domínio que o caixa e o
 * estoque ouvem. Este módulo nunca chama {@code caixa} nem {@code estoque} para produzir efeito
 * colateral, e tampouco os importa: a única coisa que a venda pergunta ao caixa, se a sessão está
 * aberta, passa por {@code CaixaParaVenda}, interface declarada aqui e implementada lá, para que a
 * dependência entre os dois módulos tenha um sentido só.
 */
@ApplicationModule(displayName = "Vendas")
package br.com.caixasimples.vendas;

import org.springframework.modulith.ApplicationModule;
