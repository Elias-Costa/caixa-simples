import { useState } from 'react'
import { Link } from 'react-router'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { sessaoAtual } from '../sessao/armazenamento'
import { useSessao } from '../sessao/useSessao'
import { VERSAO_DOS_TERMOS } from './versaoDosTermos'
import { VoltarDaPaginaPublica } from './VoltarDaPaginaPublica'

type Props = {
  /** A Conta ainda não aceitou esta versão, e esta é a única tela do administrador até o aceite. */
  obrigatoria?: boolean
}

/**
 * Os termos de uso, com o anexo de tratamento de dados: o que o Caixa Simples faz com os dados que a
 * Conta registra, e o que não faz.
 *
 * Pública, a tela pode ser lida antes de contratar. Obrigatória, aparece ao administrador logo
 * depois do login, sem o menu, até ele aceitar a versão vigente; o aceite vale para a Conta inteira,
 * e o operador nunca vê esta tela, para o balcão não parar quando sai uma versão nova. A versão vai
 * no pedido de aceite: um aparelho com o texto antigo recebe a recusa, em vez de aceitar um texto
 * que não mostrou.
 */
export function TelaDosTermos({ obrigatoria = false }: Props) {
  const { identidade, sair, atualizarIdentidade } = useSessao()
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function aceitar() {
    setErro(null)
    const sessao = sessaoAtual()
    if (!sessao || !identidade) return
    setEnviando(true)
    try {
      await contas.aceitarTermos(VERSAO_DOS_TERMOS)
      // Outro login nesta aba durante o pedido tem a identidade dele, que este aceite não muda.
      if (sessaoAtual() === sessao) atualizarIdentidade({ ...identidade, termosPendentes: false }, sessao)
    } catch (falha) {
      setErro(mensagemDe(falha))
    } finally {
      setEnviando(false)
    }
  }

  return <main className="cadastro">
    <h1>Termos de uso</h1>
    <p className="cadastro__apoio">Versão de {mostrarData(VERSAO_DOS_TERMOS)}. Valem para a Conta, o
      negócio que usa o Caixa Simples, e são aceitos pelo administrador dela.</p>

    <h2>O serviço</h2>
    <p>O Caixa Simples é um sistema de caixa e vendas para pequenos negócios, oferecido por pessoa
      natural. Cada negócio que o usa é uma Conta, com um administrador, que cadastra os outros
      usuários, e com operadores, que vendem e cuidam do caixa.</p>

    <h2>O aceite e as versões</h2>
    <p>O administrador aceita estes termos em nome da Conta no primeiro acesso. O sistema guarda a
      versão aceita, o momento e quem aceitou. Uma versão nova tem data própria: o administrador a vê
      no acesso seguinte e só continua depois de aceitá-la. Até lá vale a versão anterior, e os
      operadores continuam trabalhando.</p>

    <h2>Planos e pagamento</h2>
    <p>A Conta começa no plano gratuito. O plano pago tem a mensalidade mostrada na tela Plano antes
      do pedido. O administrador faz o pedido no aplicativo, manda o texto dele pelo canal de
      atendimento e paga por Pix; conferido o Pix, recebe um código que ativa o plano. O plano pago
      vence todo mês no dia da adesão, com aviso sete dias antes. Sem renovação, os recursos pagos
      continuam por sete dias depois do vencimento e param no oitavo, até a renovação; venda, caixa e
      cadastro nunca param. O sistema ainda não emite documento fiscal.</p>

    <h2>Disponibilidade</h2>
    <p>O Caixa Simples roda numa única máquina, sem redundância, e não garante funcionamento
      contínuo. As atualizações de rotina saem de madrugada, entre 0h e 5h no horário de Brasília; uma
      correção urgente pode sair em outro horário. Sem conexão com o servidor, o aplicativo continua
      vendendo no aparelho em que já se entrou e envia o que registrou quando a conexão volta.</p>

    <h2>Encerramento</h2>
    <p>O administrador pode pedir o encerramento da Conta a qualquer momento, pelo canal indicado no
      aviso de privacidade. Em até quinze dias do pedido, todos os registros da Conta no banco e os
      segredos dela no servidor, como as credenciais do Pix, são apagados. Não há exportação: guarde
      antes os relatórios e comprovantes que quiser. As cópias de segurança não são editadas e expiram
      sozinhas, as de hora em hora em três dias e as diárias em trinta, mais até um dia para o
      armazenamento apagar o que expirou.</p>
    <p>O Caixa Simples pode encerrar uma Conta, ou o serviço inteiro, com trinta dias de aviso ao
      e-mail do administrador. Nesse prazo o acesso continua, para a Conta guardar o que quiser, e
      depois o encerramento segue como acima. O acesso da Conta só é suspenso na hora, com aviso ao
      administrador, em caso de uso ilegal ou de ataque ao serviço. O atraso da mensalidade não
      encerra a Conta: só suspende os recursos pagos.</p>

    <h2>Lei e foro</h2>
    <p>Estes termos seguem a lei brasileira. Fica eleito o foro do domicílio da Conta.</p>

    <h2>Contato</h2>
    <p>Pedidos e comunicações sobre estes termos e sobre dados pessoais vão ao canal de atendimento
      indicado no <Link to="/privacidade">aviso de privacidade</Link>.</p>

    <h2>Anexo: tratamento de dados pessoais</h2>

    <h3>Papéis</h3>
    <p>A Conta decide sobre os dados dos seus Clientes e funcionários: é a controladora deles. O Caixa
      Simples trata esses dados em nome da Conta, como operador, conforme as instruções destes termos.
      O Caixa Simples decide sobre o cadastro da Conta e do seu administrador, isto é, o nome do
      negócio e o nome e o e-mail de acesso do administrador, que existe para executar este
      contrato.</p>

    <h3>As instruções da Conta</h3>
    <p>As instruções são as funcionalidades do sistema: cadastrar produtos, Clientes e usuários;
      registrar vendas, pagamentos, fiado e recebimentos; abrir, movimentar e fechar o caixa; controlar
      o estoque; mostrar relatórios; guardar no aparelho o que é preciso para trabalhar sem rede e
      enviar ao servidor depois; remover Clientes e usuários e encerrar a Conta, a pedido dela.</p>
    <p>O Caixa Simples não usa os dados da Conta para outra finalidade, não os vende, não os
      compartilha além dos fornecedores abaixo, não entra em contato com os Clientes da Conta e não
      leva os dados dos Clientes e funcionários para fora do Brasil. Só acessa os dados da Conta para
      manter o serviço, investigar um incidente de segurança ou atender um pedido da própria
      Conta.</p>

    <h3>Fornecedores e onde ficam</h3>
    <p>O servidor, com o aplicativo e o banco de dados, fica numa VPS da HostGator no Brasil. As
      cópias de segurança, cifradas, e o registro das remoções ficam no armazenamento da Amazon Web
      Services no Brasil. A troca de um fornecedor que guarde dados da Conta vem numa versão nova
      destes termos. A cobrança Pix integrada usa a conta da própria Conta na Efí, contratada por ela;
      em cada cobrança, o Caixa Simples manda à Efí só o valor, a chave Pix e a validade.</p>

    <h3>Sigilo</h3>
    <p>O Caixa Simples mantém em sigilo os dados da Conta, e quem tiver acesso a eles em nome do Caixa
      Simples fica sujeito ao mesmo sigilo.</p>

    <h3>Segurança</h3>
    <p>Em linhas gerais: cada Conta só enxerga os próprios dados, em toda consulta; a conexão é
      cifrada; a senha é guardada só como um resumo protegido, com no mínimo quinze caracteres, e a
      senha nova é conferida contra uma lista pública de senhas vazadas, que recebe só os cinco
      primeiros caracteres de um resumo dela, sem e-mail; o acesso de um aparelho pode ser revogado;
      as cópias de segurança são cifradas, com a chave fora do servidor; os registros de acesso duram
      sete dias e não guardam nome, contato nem e-mail; o servidor recebe as atualizações de segurança
      sozinho e só aceita acesso administrativo por chave.</p>

    <h3>Incidente de segurança</h3>
    <p>Se um incidente de segurança alcançar dado pessoal da Conta, o Caixa Simples avisa o
      administrador por e-mail em até dois dias úteis de quando souber, com o que já se sabe: o que
      aconteceu, que dados e que pessoas foram alcançados, os riscos, as medidas tomadas e o que a
      Conta pode fazer. E apoia a Conta na comunicação que caiba a ela fazer.</p>

    <h3>Pedidos dos titulares</h3>
    <p>Os Clientes e funcionários pedem à Conta o que quiserem sobre os próprios dados. No
      aplicativo, o administrador remove os dados de um Cliente sem saldo devedor nem comanda aberta
      e, ao inativar um usuário, apaga o acesso dele e, a pedido, o nome. Um pedido sobre dados da
      Conta que chegar ao Caixa Simples é encaminhado a ela.</p>

    <h3>Deveres da Conta</h3>
    <p>A Conta informa os seus Clientes e funcionários sobre o uso dos dados deles e responde pelo que
      registra no sistema. Cuida dos acessos, com cada pessoa no próprio usuário, senhas que só ela
      conheça e a inativação de quem sai, e dos aparelhos em que se entra, que guardam dados para
      trabalhar sem rede. Os campos de texto livre, como o motivo de uma sangria, de um suprimento ou
      de um ajuste de estoque e o nome e os atributos de um produto, não são lugar de dado pessoal; e
      nenhum campo é lugar de dado sensível, como saúde, religião ou origem racial, nem de documento
      pessoal.</p>

    <h3>Fim do contrato</h3>
    <p>No fim do contrato, os dados da Conta seguem o que está em Encerramento: apagados em até quinze
      dias, sem exportação, com as cópias expirando nos prazos delas.</p>

    {obrigatoria
      ? <section className="cadastro__formulario">
        <p>Para continuar, aceite estes termos em nome da Conta.</p>
        {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
        <button className="botao" type="button" disabled={enviando} onClick={() => void aceitar()}>
          {enviando ? 'Aceitando...' : 'Aceito os termos'}
        </button>
        <p><button className="botao botao--secundario" type="button" onClick={sair}>Sair</button></p>
      </section>
      : <VoltarDaPaginaPublica />}
  </main>
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}

function mensagemDe(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem conexão. Aceitar os termos precisa de rede.'
  if (falha instanceof ErroDaApi) {
    if (falha.status === 401) return 'A sessão foi encerrada. Entre de novo.'
    if (falha.status === 409) return 'Os termos mudaram desde que o aplicativo foi aberto. Recarregue a página para ler a versão nova.'
    return falha.detalhe ?? falha.titulo
  }
  return 'Não foi possível concluir. Tente de novo.'
}
