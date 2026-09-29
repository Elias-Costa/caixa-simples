import { Link } from 'react-router'

/** Aviso acessível antes do login para a Conta e para quem tiver pedido sobre seus dados. */
export function TelaDePrivacidade() {
  return <main className="cadastro">
    <h1>Aviso de privacidade</h1>
    <p>O Caixa Simples oferece um sistema de caixa para pequenos negócios. Cada Conta decide sobre
      os dados dos seus Clientes e funcionários. O Caixa Simples trata esses dados conforme as
      instruções da Conta. Para administrar o acesso e o contrato, o Caixa Simples decide sobre
      o cadastro da própria Conta e do seu administrador.</p>

    <h2>Dados usados</h2>
    <p>O sistema guarda nome e contato opcional de Cliente; nome de Usuário; e-mail de acesso e
      senha protegida por hash; identificação de quem operou cada Venda e SessaoCaixa; e os gestos
      de sincronização registrados no aparelho. A chave Pix da Conta pode ser CPF, telefone ou
      e-mail. O histórico da Venda é preservado enquanto a Conta existe.</p>

    <h2>Onde ficam e por quanto tempo</h2>
    <p>O servidor e as cópias cifradas do banco ficam no Brasil. O aplicativo guarda retratos e
      gestos no aparelho para funcionar sem rede. As cópias horárias expiram em três dias e as
      cópias diárias em trinta dias. Os logs expiram em sete dias e não registram nome, contato
      nem e-mail. Depois de uma restauração, as remoções posteriores à cópia são reaplicadas
      antes de liberar o acesso.</p>

    <h2>Pedidos de remoção</h2>
    <p>Se você é Cliente ou funcionário de um negócio que usa o sistema, peça a remoção à própria
      Conta. O administrador da Conta pode remover no aplicativo os dados de um Cliente sem saldo
      devedor nem comanda aberta. O nome passa a “Cliente removido”, o contato é apagado e as
      Vendas antigas continuam válidas. Ao inativar um Usuário, o acesso e a credencial são
      apagados; a pedido, o administrador também pode remover o nome dele.</p>

    <p>Somente o administrador pede o encerramento da própria Conta pelo e-mail
      {' '}<a href="mailto:ecrprofessional@gmail.com">ecrprofessional@gmail.com</a>.
      Inclua apenas a identificação da Conta e o pedido, sem dados de Cliente. Depois do pedido,
      os dados da Conta e seus segredos são apagados em até quinze dias. O Google pode processar
      a mensagem de e-mail fora do Brasil. Esse canal é usado para executar o pedido de
      encerramento; os demais dados do sistema seguem armazenados no Brasil.</p>

    <p><Link to="/entrar">Voltar para o acesso</Link></p>
  </main>
}
