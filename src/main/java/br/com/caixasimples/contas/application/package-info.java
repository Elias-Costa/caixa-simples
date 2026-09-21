/**
 * Casos de uso de conta e autenticação, e a API que os outros módulos enxergam.
 *
 * <p>O Modulith expõe apenas o pacote-base de cada módulo; todo subpacote é interno até que se diga
 * o contrário. A anotação abaixo diz o contrário para este pacote, e só para ele: quem precisa
 * perguntar algo sobre a conta em operação, como o módulo de estoque ao decidir se dá baixa numa
 * venda, chama {@code ContaService}. {@code internal} continua oculto, então a entidade da conta,
 * com seus mutadores, e o repositório sem filtro de tenant não vazam para fora do módulo.
 *
 * <p>Custo aceito, o mesmo do cadastro: {@code AutenticacaoService} e {@code UsuarioService} ficam
 * visíveis junto, sem ter consumidor externo; o segundo é a gestão de usuários da conta, chamada
 * só pela camada web deste módulo. Vale o critério de fronteira de sempre: chamada direta faz
 * pergunta, efeito colateral entre módulos é evento.
 */
@NamedInterface("application")
package br.com.caixasimples.contas.application;

import org.springframework.modulith.NamedInterface;
