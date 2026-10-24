/**
 * A ajuda de cada tela, até três linhas, num lugar só para ser lida e revista de uma vez. Cada dúvida
 * que a tela resolve é um contato de suporte a menos.
 */
export const AJUDA = {
  vender: [
    'Busque o produto pelo nome ou pelo código; o leitor de código de barras lança direto.',
    'Registre o pagamento na forma que o cliente usou; no dinheiro, o troco aparece ao digitar o valor recebido.',
    'Sem internet, a venda fica guardada no aparelho e é enviada quando a rede voltar.',
  ],
  caixa: [
    'Abra o caixa com o dinheiro da gaveta antes da primeira venda do dia.',
    'Sangria é dinheiro que sai da gaveta; suprimento é troco que entra.',
    'Ao fechar, conte a gaveta: a tela mostra a falta ou a sobra, e as vendas de cada sessão ficam aqui para conferir.',
  ],
  produtos: [
    'Com estoque é o item comprado pronto, que se conta na prateleira; Sem estoque é serviço ou preparado na hora.',
    'O tipo não muda depois do cadastro.',
    'Inativar tira o item da venda e mantém o histórico.',
  ],
  clientes: [
    'O cliente é obrigatório no fiado e opcional nas outras vendas.',
    'Inativar tira o cliente das buscas, e reativar o devolve.',
    'Remover, só do administrador, apaga o nome e o contato de vez; as vendas continuam no histórico sem eles.',
  ],
  fiado: [
    'Cada linha é um cliente, com o total que ele deve.',
    'Toque no cliente para ver as vendas e registrar o que ele pagou, em dinheiro, Pix ou cartão.',
    'O recebimento pede o seu caixa aberto e entra nele.',
  ],
  sincronizacao: [
    'Por enviar é o que foi feito sem internet e ainda não chegou ao servidor; sai sozinho quando a rede volta.',
    'Em revisão é o que o servidor aplicou com ajuste ou recusou: confira e toque em Conferir.',
    'O administrador vê também as revisões de todos os aparelhos da Conta.',
  ],
  minhaConta: [
    'Seus dados, a troca do e-mail de entrada e a da senha.',
    'Sair de todos os aparelhos encerra o acesso em todo aparelho, inclusive neste.',
    'Use se um aparelho sumiu; se a senha pode ter vazado, troque a senha.',
  ],
  estoque: [
    'Entrada é a mercadoria que chegou, pela quantidade comprada.',
    'Ajuste é perda, quebra ou contagem, sempre com motivo.',
    'O mínimo de cada item faz o alerta de estoque baixo.',
  ],
  relatorios: [
    'Escolha o período e, se quiser, um operador.',
    'O faturamento conta a venda no dia em que ela foi concluída; o fluxo de caixa é só o dinheiro da gaveta.',
    'Num dia só aparecem as conferências do cartão e do Pix, para bater com os extratos da maquininha e do banco.',
  ],
  usuarios: [
    'Crie um acesso para cada funcionário, com uma senha provisória que ele troca no primeiro acesso.',
    'Se ele esquecer a senha, redefina uma provisória.',
    'Inativar corta o acesso na hora.',
  ],
  configuracao: [
    'O nome do negócio sai no cabeçalho e no comprovante.',
    'Estoque liga o controle de saldo dos itens; NSU exige o número do comprovante da maquininha no cartão.',
    'A chave Pix gera o QR do Pix lançado à mão.',
  ],
  plano: [
    'O plano atual, o vencimento e o que cada plano inclui.',
    'Para contratar ou renovar, faça o pedido e pague pelo Pix.',
    'O código recebido depois da conferência do pagamento ativa o plano.',
  ],
} as const satisfies Record<string, readonly string[]>
