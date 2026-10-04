// Gera o código de ativação de um pedido de plano, depois de o Pix ser conferido no extrato.
//
// Uso, com o mesmo segredo do ambiente publicado:
//   CAIXA_SIMPLES_PLANO_SECRET=... node operacao/plano/codigo.mjs <id do pedido> <plano>
//
// O id e o plano vêm do texto do pedido que o administrador mandou. O plano pode ir como no texto
// ("Caixa Simples", "Completo") ou como no sistema (CAIXA_SIMPLES, COMPLETO). O código é a
// assinatura HMAC-SHA256 do id do pedido e do plano; a aplicação a recalcula para conferir, então
// este script não toca o banco nem a Conta, e o mesmo pedido sempre dá o mesmo código.
//
// Sem dependência: só o Node. O teste ao lado confere o mesmo vetor fixo que a suíte Java confere
// na aplicação.
import { createHmac } from 'node:crypto'
import { pathToFileURL } from 'node:url'

const ALFABETO = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const PLANOS = ['CAIXA_SIMPLES', 'COMPLETO']
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const MINIMO_DE_BYTES = 32

/** O código do pedido, em quatro grupos de quatro caracteres, como a aplicação o mostra. */
export function codigoDoPedido(segredo, pedidoId, plano) {
  if (!segredo || Buffer.byteLength(segredo, 'utf8') < MINIMO_DE_BYTES) {
    throw new Error(`CAIXA_SIMPLES_PLANO_SECRET precisa de ao menos ${MINIMO_DE_BYTES} bytes`)
  }
  const id = String(pedidoId ?? '').trim().toLowerCase()
  if (!UUID.test(id)) throw new Error(`id de pedido invalido: ${pedidoId}`)
  const doSistema = String(plano ?? '').trim().toUpperCase().replace(/\s+/g, '_')
  if (!PLANOS.includes(doSistema)) {
    throw new Error(`plano invalido: ${plano}; use Caixa Simples ou Completo`)
  }

  const assinatura = createHmac('sha256', Buffer.from(segredo, 'utf8'))
    .update(`pedido-de-plano|${id}|${doSistema}`, 'utf8')
    .digest()
  return base32(assinatura.subarray(0, 10)).match(/.{4}/g).join('-')
}

// Cada 5 bits, do mais significativo ao menos, viram um caractere do alfabeto de Crockford, que
// não tem I, L, O nem U, as letras que se confundem com algarismos ao ditar ou digitar.
function base32(bytes) {
  let acumulado = 0
  let bits = 0
  let texto = ''
  for (const byte of bytes) {
    acumulado = (acumulado << 8) | byte
    bits += 8
    while (bits >= 5) {
      bits -= 5
      texto += ALFABETO[(acumulado >>> bits) & 0x1f]
    }
    acumulado &= (1 << bits) - 1
  }
  return texto
}

// Roda como comando só quando chamado direto; o teste importa a função sem executar nada.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [pedidoId, ...plano] = process.argv.slice(2)
  try {
    console.log(codigoDoPedido(process.env.CAIXA_SIMPLES_PLANO_SECRET, pedidoId, plano.join(' ')))
  } catch (erro) {
    console.error(erro.message)
    console.error('Uso: CAIXA_SIMPLES_PLANO_SECRET=... node operacao/plano/codigo.mjs <id do pedido> <plano>')
    process.exit(1)
  }
}
