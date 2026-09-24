/**
 * O domínio de pagamentos: Strategy, contrato de pagamento e porta da cobrança Pix.
 *
 * <p>Este pacote é exposto aos outros módulos, ao contrário do {@code domain} dos módulos que têm
 * agregado. O motivo de ocultar um domínio é não deixar vazar a raiz do agregado com seus
 * mutadores; aqui não há agregado nem mutador. {@code SolicitacaoPagamento} e
 * {@code ResultadoPagamento} são records imutáveis, e quem chama {@code PaymentService} precisa
 * nomear os dois: constrói o pedido e lê a resposta. A cobrança integrada também usa
 * {@code CobrancaPix} e {@code PixGateway} sem expor o adapter da Efí.
 *
 * <p>Custo aceito: outro módulo consegue chamar as fábricas de {@code ResultadoPagamento} direto,
 * sem passar pelo Strategy. A regra continua sendo pagar por {@code PaymentService}, e ela vale
 * por disciplina e por revisão, não por compilação.
 */
@NamedInterface("domain")
package br.com.caixasimples.pagamentos.domain;

import org.springframework.modulith.NamedInterface;
