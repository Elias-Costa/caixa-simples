import { Link } from 'react-router'
import { VoltarDaPaginaPublica } from './VoltarDaPaginaPublica'

/**
 * O aviso de privacidade: quem trata cada dado, para quê, com que base, onde fica e por quanto
 * tempo, com quem é compartilhado e como pedir cada direito do art. 18 da LGPD. Público, se lê antes
 * de entrar, pela tela de login, e de dentro do aplicativo, pelo rodapé.
 *
 * O canal de atendimento ainda não tem endereço: até ele existir, o serviço só tem Contas de teste,
 * e o único endereço publicado é o do pedido de encerramento.
 */
export function TelaDePrivacidade() {
  return <main className="cadastro">
    <h1>Aviso de privacidade</h1>
    <p className="cadastro__apoio">Versão de 25/10/2026. Diz como o Fechadia trata os dados
      pessoais de quem usa o sistema e de quem aparece nele, conforme a Lei Geral de Proteção de Dados
      (LGPD).</p>

    <h2>Quem trata os dados</h2>
    <p>O Fechadia é um sistema de caixa e vendas para pequenos negócios, oferecido por pessoa
      natural. Cada negócio que o usa é uma Conta, com um administrador, que cadastra os outros
      usuários, e com operadores, que vendem e cuidam do caixa.</p>
    <p>A Conta decide sobre os dados dos seus Clientes e funcionários: é a controladora deles. Ela
      informa essas pessoas sobre o uso dos dados e responde aos pedidos delas. O Fechadia trata
      esses dados em nome da Conta, como operador, só conforme as instruções dos termos de uso: mantém
      a segurança e o sigilo deles e avisa a Conta, em até dois dias úteis, de um incidente de
      segurança que alcance dado dela.</p>
    <p>O Fechadia decide, como controlador, sobre o cadastro da Conta e do seu administrador, que
      existe para executar o contrato, e sobre os registros de segurança descritos abaixo.</p>

    <h2>Contato</h2>
    <p>Os pedidos sobre dados pessoais, o pedido de encerramento da Conta e as comunicações da
      Autoridade Nacional de Proteção de Dados (ANPD) vão ao canal de atendimento do Fechadia: um
      endereço de e-mail no domínio do serviço, num provedor que guarda as mensagens no Brasil. O
      endereço é publicado aqui antes de o primeiro negócio real usar o sistema; até lá, o serviço só
      tem Contas de teste. O Fechadia não indica encarregado, como a ANPD permite ao agente de
      pequeno porte: o canal de atendimento faz esse papel.</p>
    <p>Até o canal existir, somente o administrador pede o encerramento da própria Conta, pelo e-mail
      {' '}<a href="mailto:ecrprofessional@gmail.com">ecrprofessional@gmail.com</a>. Inclua apenas a
      identificação da Conta e o pedido, sem dados de Cliente. O Google pode processar a mensagem fora
      do Brasil. Esse endereço serve só ao encerramento; os demais dados do sistema seguem no
      Brasil.</p>

    <h2>Dados, para que servem e com que base</h2>
    <p>Nos dados que a Conta registra, a base legal é escolhida por ela, que é a controladora deles. No
      cadastro da Conta e do administrador, a base é a execução do contrato.</p>
    <ul>
      <li><strong>Clientes:</strong> nome e contato opcional, para vincular à venda, vender fiado e
        emitir comprovante. Enquanto houver dívida, o Cliente devedor é mantido pela proteção do
        crédito.</li>
      <li><strong>Funcionários:</strong> nome e perfil, para registrar quem operou cada venda e cada
        caixa; e-mail de acesso e senha, guardada só como um resumo protegido, para entrar no
        sistema.</li>
      <li><strong>Administrador e Conta:</strong> nome e tipo do negócio, plano e vencimento; nome,
        e-mail de acesso e senha do administrador; o aceite dos termos e os pedidos de plano, para
        executar o contrato.</li>
      <li><strong>Pix:</strong> a chave Pix da Conta, que pode ser o CPF, o telefone ou o e-mail do
        dono, e a cidade do recebedor, que o aparelho põe, com o nome do negócio, no QR do Pix manual,
        à vista de cada cliente que paga; e o código copia e cola de cada cobrança do Pix integrado,
        para cobrar e conferir o pagamento. A chave aleatória do banco não identifica o dono.</li>
      <li><strong>Envio do aparelho:</strong> uma cópia do que o aparelho registrou sem rede, inclusive
        nome e contato de Cliente, para que um envio repetido não repita o efeito e para o administrador
        revisar o que foi recusado. A cópia do Cliente é esvaziada a cada edição e na remoção dele.</li>
      <li><strong>Histórico do negócio:</strong> vendas, caixa e estoque guardam quem operou e o
        Cliente vinculado. Os campos de texto livre, como o motivo de uma sangria, não são lugar de dado
        pessoal.</li>
      <li><strong>Atendimento:</strong> os pedidos ao canal e as respostas, para atender os direitos
        abaixo, por obrigação legal; as mensagens do pedido de plano, pelo contrato; e o extrato do
        banco que recebe a mensalidade, com o nome e o documento de quem paga, para conferir o Pix do
        plano, guardado pela obrigação fiscal. O que foi pedido e respondido é guardado como prova,
        para a defesa em processo judicial, administrativo ou arbitral.</li>
    </ul>
    <p>O sistema não pede dado sensível, como saúde, religião ou origem racial, nem documento pessoal
      além da chave Pix.</p>

    <h2>Registros de segurança</h2>
    <p>Para proteger o serviço, que todas as Contas dividem, o Fechadia registra, como controlador
      e com base no seu legítimo interesse, os eventos de segurança: o login certo e o recusado, a
      recusa de acesso, a revogação de acesso, as ações do administrador sobre a Conta e o erro
      inesperado do servidor. Cada registro leva o endereço IP de origem, a identificação da Conta, do
      usuário e do alvo, o motivo numa categoria fixa e, na recusa e no erro, o caminho pedido, sem os
      parâmetros. Não leva nome, e-mail, contato, senha nem a chave de acesso.</p>
    <p>Servem para perceber e conter tentativas de adivinhar senhas, o uso de um acesso já revogado,
      uma ação do administrador vinda de origem estranha e uma enxurrada de avisos falsos de pagamento,
      e para achar a causa de um erro. Duram sete dias, ficam no servidor, no Brasil, fora das cópias de segurança, e só quem mantém o
      Fechadia os lê. A Conta não os lê, e eles não servem para acompanhar o trabalho de
      ninguém.</p>
    <p>Para conter tentativas em série no login, a aplicação guarda na memória, por quinze minutos, a
      origem e um resumo do e-mail tentado, e recusa por esse tempo quem insiste.</p>
    <p>Num incidente de segurança, o trecho desses registros no período do incidente é guardado
      cifrado fora do servidor, no Brasil, até o incidente ser encerrado ou, se comunicado à ANPD, até
      o fim do processo nela. Todo incidente, comunicado ou não, fica registrado por cinco anos, por
      obrigação regulatória.</p>
    <p>Você pode se opor a esse tratamento pelo canal de atendimento. O pedido é respondido em até
      quinze dias, considerando que o registro só protege as Contas se valer para todo acesso.</p>

    <h2>Onde ficam e por quanto tempo</h2>
    <p>O servidor, com o aplicativo e o banco de dados, e as cópias de segurança, cifradas, ficam no
      Brasil.</p>
    <ul>
      <li><strong>Cadastro e histórico:</strong> enquanto a Conta existir. O Cliente removido passa a
        “Cliente removido”, sem contato, e as vendas antigas continuam válidas. O usuário inativado
        continua no histórico, com o nome, que a pedido também é removido; a credencial dele, com o
        e-mail e a senha, é apagada na inativação. Se o registro das remoções estiver fora do ar, o
        acesso termina mesmo assim, e a credencial fica até o administrador concluir a inativação,
        quando o registro voltar.</li>
      <li><strong>Cópias de segurança:</strong> as de hora em hora expiram em três dias e as diárias em
        trinta, mais até um dia para o armazenamento apagar o que expirou. Não são editadas: depois de
        uma restauração, as remoções feitas depois da cópia são reaplicadas antes de liberar o
        acesso.</li>
      <li><strong>Registro das remoções:</strong> a identificação da Conta, do que foi removido, do
        momento e de quem pediu, sem nome nem contato, por quarenta e cinco dias, para reaplicar a
        remoção numa restauração.</li>
      <li><strong>Registros de segurança:</strong> sete dias, como acima.</li>
      <li><strong>Sessão no navegador:</strong> o acesso e a identificação de quem entrou ficam até
        Sair.</li>
      <li><strong>No aparelho:</strong> o aplicativo guarda no aparelho em que se entra o que precisa
        para trabalhar sem rede: o cadastro de produtos e de Clientes, com nome e contato, as vendas e o
        caixa lidos do servidor, a chave Pix e a cidade do QR do Pix manual, o que foi registrado sem
        rede até ser enviado e a marca de quem já viu os primeiros passos. Sair não apaga esses
        dados. Para apagá-los, use Apagar os dados deste aparelho na tela de entrada, depois de entrar
        com rede e esperar o envio do que foi registrado sem rede, que se perde se ainda não tiver
        chegado ao servidor; ou limpe os dados do site nas configurações do navegador. Faça isso antes
        de se desfazer de um aparelho, no aparelho de quem foi inativado e em cada aparelho da Conta
        encerrada: o Fechadia não alcança o aparelho.</li>
      <li><strong>Depois do encerramento:</strong> todos os registros da Conta no banco e os segredos
        dela no servidor são apagados em até quinze dias do pedido. As cópias de segurança feitas antes
        guardam a Conta até expirarem, por até mais trinta e um dias, e uma restauração reaplica o
        encerramento, sem trazer a Conta de volta.</li>
      <li><strong>Atendimento:</strong> os pedidos ao canal e as respostas ficam cinco anos depois da
        resposta; o pedido de encerramento e a prova de que foi cumprido, cinco anos depois do
        encerramento; as mensagens do pedido de plano, enquanto a Conta existir e cinco anos depois; a
        cópia do extrato, pelo prazo fiscal.</li>
    </ul>

    <h2>Com quem é compartilhado</h2>
    <ul>
      <li><strong>HostGator</strong>, que hospeda o servidor no Brasil e por isso guarda tudo o que está
        nele.</li>
      <li><strong>Amazon Web Services</strong>, que guarda no Brasil as cópias de segurança, cifradas
        com uma chave que fica fora do servidor, e o registro das remoções.</li>
      <li><strong>Efí</strong>, contratada pela própria Conta para a cobrança Pix integrada: em cada
        cobrança, o Fechadia manda só o valor, a chave Pix e a validade.</li>
      <li><strong>O provedor do canal de atendimento</strong>, que guarda as mensagens no Brasil.</li>
      <li><strong>Google</strong>, pelo Gmail, só no pedido de encerramento enquanto o canal não existe,
        possivelmente fora do Brasil.</li>
      <li><strong>O banco que recebe a mensalidade</strong>, que guarda o próprio extrato.</li>
    </ul>
    <p>Os dados não são vendidos nem usados em publicidade, e o Fechadia não entra em contato com
      os Clientes da Conta. Fora o pedido de encerramento pelo Gmail, nenhum dado pessoal sai do
      Brasil. A troca de um fornecedor que guarde dados da Conta vem numa versão nova dos termos de
      uso.</p>

    <h2>Seus direitos</h2>
    <p>A LGPD, no art. 18, garante a quem tem dados tratados: a confirmação de que há tratamento; o
      acesso aos dados; a correção; a anonimização, o bloqueio ou a eliminação do que for
      desnecessário, excessivo ou tratado fora da lei; a portabilidade; a eliminação do que foi tratado
      com consentimento; a informação sobre com quem os dados são compartilhados; a informação sobre a
      possibilidade de não consentir; e a revogação do consentimento. Também garante a oposição a um
      tratamento e a petição à ANPD.</p>
    <p><strong>A quem pedir.</strong> Cliente ou funcionário de um negócio pede à própria Conta, que
      decide sobre os seus dados. O pedido que chegar ao Fechadia é encaminhado à Conta, e você
      recebe resposta, em até dois dias úteis. O administrador, sobre o próprio cadastro e o da Conta,
      e quem aparece nos registros de segurança pedem ao Fechadia pelo canal de atendimento, com
      resposta em até quinze dias do pedido.</p>
    <ul>
      <li><strong>Confirmação e acesso:</strong> cada usuário vê o próprio nome, e-mail e perfil na tela
        Minha conta, e o administrador vê a Conta em Configuração e em Plano. O administrador que pedir ao
        Fechadia recebe a declaração completa dos seus dados, de onde vieram, para que servem e
        por quanto tempo ficam, no e-mail de acesso, depois de confirmada a identidade de quem
        pede.</li>
      <li><strong>Correção:</strong> o administrador corrige o nome de cada usuário e o do negócio;
        cada usuário troca o próprio e-mail na tela Minha conta; o Cliente é corrigido pela Conta, na edição
        do cadastro.</li>
      <li><strong>Anonimização, bloqueio e eliminação:</strong> o administrador remove os dados de um
        Cliente sem saldo devedor nem comanda aberta; ao inativar um usuário, apaga o acesso dele e, a
        pedido, o nome; e pode pedir o encerramento da Conta, que apaga tudo em até quinze dias.</li>
      <li><strong>Portabilidade:</strong> não há exportação dos dados enquanto a ANPD não regulamentar
        esse direito; os relatórios e comprovantes do aplicativo ficam à disposição para a Conta
        guardar o que quiser.</li>
      <li><strong>Compartilhamento:</strong> está na seção Com quem é compartilhado.</li>
      <li><strong>Consentimento:</strong> nenhum tratamento do sistema se baseia em consentimento; por
        isso não há consentimento a negar ou revogar.</li>
      <li><strong>Oposição:</strong> aos registros de segurança, como descrito na seção deles.</li>
      <li><strong>ANPD:</strong> quem tiver um pedido não atendido pode peticionar à ANPD.</li>
    </ul>

    <h2>Versões e termos</h2>
    <p>A data no topo muda a cada versão deste aviso. Os <Link to="/termos">termos de uso</Link>, com
      o anexo de tratamento de dados, dizem o que o Fechadia faz com os dados que a Conta registra
      e o que não faz.</p>

    <VoltarDaPaginaPublica />
  </main>
}
