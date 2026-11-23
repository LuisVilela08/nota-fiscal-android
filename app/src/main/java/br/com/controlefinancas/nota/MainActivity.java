package br.com.controlefinancas.nota;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONTokener;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abre a nota fiscal (NFC-e) num navegador interno do próprio celular, lê o texto da página
 * e entrega ao app Controle de Finanças, que abre o Novo lançamento já preenchido.
 *
 * Se o site recusar o pedido, tenta outras formas de se apresentar (sem o aviso do aplicativo,
 * com a identificação do Chrome, etc.) e, no fim, mostra o resultado de cada tentativa.
 */
public class MainActivity extends Activity {

    private static final Pattern ENDERECO = Pattern.compile("https?://\\S+");
    private static final Pattern NOTA = Pattern.compile("valor a pagar|vl\\.? ?total|qtd\\. total", Pattern.CASE_INSENSITIVE);
    private static final int MAX_CHECAGENS = 6;
    private static final String NOTA_TESTE = "NOTA DE TESTE\nItem de teste (Código: 1 ) Qtde.:1 UN: UN Vl. Unit.: 1,23 Vl. Total 1,23\nQtd. total de itens:\n1\nValor a pagar R$:\n1,23\n";

    // cada tentativa: {sem o aviso do aplicativo, identificação do Chrome, simula o objeto "chrome" da página}
    private static final boolean[][] TENTATIVAS = {
            {true, false, false},
            {true, true, false},
            {true, true, true},
    };
    private static final String[] NOMES = {
            "sem aviso do app, identificação padrão",
            "sem aviso do app, identificação do Chrome",
            "sem aviso do app, Chrome completo",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder relatorio = new StringBuilder();
    private FrameLayout raiz;
    private WebView web;
    private LinearLayout painel;
    private TextView status;
    private ProgressBar progresso;
    private Button botaoNavegador;
    private Button botaoPagina;
    private Button botaoTeste;
    private Button botaoAbrir;
    private Button botaoEnviarTeste;
    private String alvo;
    private int tentativa = -1;
    private int checagens = 0;
    private boolean enviado = false;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);

        raiz = new FrameLayout(this);

        painel = new LinearLayout(this);
        painel.setOrientation(LinearLayout.VERTICAL);
        painel.setGravity(Gravity.CENTER);
        painel.setBackgroundColor(Color.WHITE);
        painel.setPadding(dp(24), dp(24), dp(24), dp(24));
        raiz.addView(painel, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.parseColor("#0f172a"));
        painel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progresso = new ProgressBar(this);
        painel.addView(progresso, new LinearLayout.LayoutParams(-2, -2));

        botaoNavegador = botao("Abrir no navegador", v -> abrirNoNavegador());
        botaoPagina = botao("Ver a página", v -> painel.setVisibility(View.GONE));
        botaoTeste = botao("Testar abertura do Controle", v -> status.setText(diagnostico()));
        botaoAbrir = botao("Abrir o Controle pelo ícone", v -> abrirPeloIcone());
        botaoEnviarTeste = botao("Enviar nota de teste ao Controle", v -> enviarTeste());
        setContentView(raiz);

        alvo = descobrirEndereco(getIntent());
        if (alvo == null) {
            mostrar("Nota Fiscal (versão 5)\n\nLeia o QR Code do cupom com a câmera do celular e toque no link. Ou, com a nota aberta no navegador, toque em Compartilhar e escolha Nota Fiscal.", false);
            botaoTeste.setVisibility(View.VISIBLE);
            botaoAbrir.setVisibility(View.VISIBLE);
            botaoEnviarTeste.setVisibility(View.VISIBLE);
            return;
        }
        proxima();
    }

    /** Começa a próxima tentativa com um navegador interno novo (sem cookies da tentativa anterior). */
    private void proxima() {
        if (enviado || isFinishing()) return;
        tentativa++;
        if (tentativa >= TENTATIVAS.length) {
            falhar("Não consegui ler a nota.\n\n" + relatorio);
            return;
        }
        checagens = 0;
        mostrar("Lendo a nota… (tentativa " + (tentativa + 1) + " de " + TENTATIVAS.length + ")", true);
        if (web != null) {
            raiz.removeView(web);
            web.destroy();
        }
        CookieManager.getInstance().removeAllCookies(null);
        web = new WebView(this);
        raiz.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        configurar(web, TENTATIVAS[tentativa]);
        final int minha = tentativa;
        web.loadUrl(alvo);
        handler.postDelayed(() -> verificar(minha), 2500);
    }

    private void configurar(WebView w, boolean[] modo) {
        WebSettings ajustes = w.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);
        if (modo[1]) {
            ajustes.setUserAgentString(ajustes.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        }
        if (modo[0] && WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // não enviar o cabeçalho que identifica este aplicativo
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(ajustes, Collections.<String>emptySet());
        }
        if (modo[2] && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w,
                    "if(!window.chrome){window.chrome={runtime:{},app:{isInstalled:false},loadTimes:function(){},csi:function(){}};}",
                    Collections.singleton("*"));
        }
        final int minha = tentativa;
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest pedido, WebResourceError erro) {
                if (pedido.isForMainFrame() && minha == tentativa) {
                    registrar("não abriu a página (sem internet?)");
                    proxima();
                }
            }
        });
    }

    /** Pergunta à página o texto que ela mostra; se já for a nota, entrega ao app. */
    private void verificar(int deQuem) {
        if (enviado || isFinishing() || deQuem != tentativa) return;
        web.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()", valor -> {
            if (enviado || isFinishing() || deQuem != tentativa) return;
            String texto = decodificar(valor);
            if (texto.length() > 150 && NOTA.matcher(texto).find()) {
                enviar(texto);
                return;
            }
            if (texto.toLowerCase().contains("request rejected")) {
                registrar("recusada pelo site");
                proxima();
                return;
            }
            checagens++;
            if (checagens >= MAX_CHECAGENS) {
                registrar("a página não carregou a tempo");
                proxima();
                return;
            }
            handler.postDelayed(() -> verificar(deQuem), 1500);
        });
    }

    private void registrar(String resultado) {
        relatorio.append("• Tentativa ").append(tentativa + 1).append(" (").append(NOMES[tentativa]).append("): ").append(resultado).append("\n");
    }

    private void enviar(String texto) {
        if (enviado) return;
        enviado = true;
        String compacto = compactar(texto);
        try {
            // 1º: entrega pelo "Compartilhar" do aplicativo instalado (abre dentro dele, sem navegador)
            if (compartilharComControle(compacto)) {
                finish();
                return;
            }
            // 2º: plano B, por endereço (pode abrir no navegador)
            abrirControle(getString(R.string.app_url) + "/compartilhar#text=" + Uri.encode(compacto));
        } catch (Exception e) {
            enviado = false;
            falhar("Não consegui abrir o app Controle de Finanças.");
            return;
        }
        finish();
    }

    /** Reduz o texto: tira o rodapé da nota e os espaços repetidos (o endereço de entrega tem limite de tamanho). */
    private String compactar(String t) {
        int i = t.indexOf("\nConsumidor");
        if (i > 0) t = t.substring(0, i);
        t = t.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
        return t.length() > 9000 ? t.substring(0, 9000) : t;
    }

    /** Envia o texto ao Controle pelo Compartilhar do Android, direto ao aplicativo instalado. */
    private boolean compartilharComControle(String texto) {
        String pacote = acharAplicativoInstalado(null);
        if (pacote == null) return false;
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_TEXT, texto);
        s.setPackage(pacote);
        try {
            startActivity(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void enviarTeste() {
        if (compartilharComControle(NOTA_TESTE)) return;
        status.setText("Não consegui enviar ao Controle. Se o app está instalado, remova-o e instale de novo pelo Chrome (menu ⋮ › Instalar aplicativo).");
    }

    private void abrirPeloIcone() {
        String pacote = acharAplicativoInstalado(null);
        Intent li = pacote == null ? null : getPackageManager().getLaunchIntentForPackage(pacote);
        if (li == null) {
            status.setText("Não encontrei o aplicativo Controle instalado.");
            return;
        }
        startActivity(li);
    }

    /** Abre o endereço direto no Controle de Finanças instalado como aplicativo; se não achar, usa o caminho normal (navegador). */
    private void abrirControle(String url) {
        Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        it.addCategory(Intent.CATEGORY_BROWSABLE);
        String pacote = acharAplicativoInstalado(it);
        if (pacote != null) {
            Intent direto = new Intent(it);
            direto.setPackage(pacote);
            try {
                startActivity(direto);
                return;
            } catch (Exception e) {
                // cai no caminho normal
            }
        }
        startActivity(it);
    }

    /** Procura o Controle de Finanças instalado como aplicativo (os "WebAPK" do Chrome têm "webapk" no nome do pacote). */
    private String acharAplicativoInstalado(Intent it) {
        PackageManager pm = getPackageManager();
        try {
            for (ApplicationInfo a : pm.getInstalledApplications(0)) {
                if (a.packageName != null && a.packageName.toLowerCase().contains("webapk")
                        && String.valueOf(pm.getApplicationLabel(a)).toLowerCase().contains("controle")) {
                    return a.packageName;
                }
            }
        } catch (Exception e) {
            // segue para a outra forma
        }
        if (it == null) return null;
        try {
            List<ResolveInfo> lista = pm.queryIntentActivities(it, PackageManager.MATCH_ALL);
            for (ResolveInfo r : lista) {
                String pacote = r.activityInfo.packageName;
                if (pacote != null && pacote.toLowerCase().contains("webapk")) return pacote;
            }
        } catch (Exception e) {
            // sem resposta
        }
        return null;
    }

    /** Texto de teste: o que o Android mostra sobre o Controle de Finanças instalado. */
    private String diagnostico() {
        StringBuilder sb = new StringBuilder("Versão 5\n\nAplicativos do tipo webapk instalados:\n");
        PackageManager pm = getPackageManager();
        int n = 0;
        try {
            for (ApplicationInfo a : pm.getInstalledApplications(0)) {
                if (a.packageName != null && a.packageName.toLowerCase().contains("webapk")) {
                    sb.append("• ").append(pm.getApplicationLabel(a)).append(" — ").append(a.packageName).append("\n");
                    n++;
                }
            }
        } catch (Exception e) {
            sb.append("(erro ao listar: ").append(e.getClass().getSimpleName()).append(")\n");
        }
        if (n == 0) sb.append("(nenhum)\n");
        Intent teste = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.app_url) + "/novo"));
        teste.addCategory(Intent.CATEGORY_BROWSABLE);
        sb.append("\nPara abrir o link do Controle, o Android oferece:\n");
        try {
            for (ResolveInfo r : pm.queryIntentActivities(teste, PackageManager.MATCH_ALL)) {
                sb.append("• ").append(r.activityInfo.packageName).append("\n");
            }
        } catch (Exception e) {
            sb.append("(erro ao consultar)\n");
        }
        String pwa = acharAplicativoInstalado(teste);
        sb.append("\nEscolhido: ").append(String.valueOf(pwa));
        if (pwa != null) {
            Intent envio = new Intent(Intent.ACTION_SEND);
            envio.setType("text/plain");
            envio.setPackage(pwa);
            sb.append("\n\nAceita receber compartilhamento: ");
            try {
                sb.append(pm.queryIntentActivities(envio, PackageManager.MATCH_ALL).isEmpty() ? "NÃO (remova e instale o Controle de novo)" : "sim");
            } catch (Exception e) {
                sb.append("sem resposta");
            }
        }
        return sb.toString();
    }

    private void falhar(String mensagem) {
        if (enviado) return;
        mostrar(mensagem, false);
        status.setTextSize(15);
        botaoNavegador.setVisibility(View.VISIBLE);
        botaoPagina.setVisibility(View.VISIBLE);
    }

    private void mostrar(String mensagem, boolean carregando) {
        painel.setVisibility(View.VISIBLE);
        status.setTextSize(17);
        status.setText(mensagem);
        progresso.setVisibility(carregando ? View.VISIBLE : View.GONE);
        botaoNavegador.setVisibility(View.GONE);
        botaoPagina.setVisibility(View.GONE);
        botaoTeste.setVisibility(View.GONE);
        botaoAbrir.setVisibility(View.GONE);
        botaoEnviarTeste.setVisibility(View.GONE);
    }

    /** Abre a nota no navegador padrão (sem voltar para este app). */
    private void abrirNoNavegador() {
        try {
            Intent it = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
            it.setData(Uri.parse(alvo));
            startActivity(it);
        } catch (Exception e) {
            status.setText("Não encontrei um navegador.");
        }
    }

    private String descobrirEndereco(Intent it) {
        if (it == null) return null;
        String acao = it.getAction();
        if (Intent.ACTION_VIEW.equals(acao) && it.getData() != null) {
            return it.getDataString();
        }
        if (Intent.ACTION_SEND.equals(acao)) {
            String t = it.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                Matcher m = ENDERECO.matcher(t);
                if (m.find()) return m.group();
            }
        }
        return null;
    }

    private String decodificar(String valor) {
        try {
            Object o = new JSONTokener(valor).nextValue();
            return o instanceof String ? (String) o : "";
        } catch (Exception e) {
            return "";
        }
    }

    private Button botao(String texto, View.OnClickListener ao) {
        Button b = new Button(this);
        b.setText(texto);
        b.setAllCaps(false);
        b.setOnClickListener(ao);
        b.setVisibility(View.GONE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(12);
        painel.addView(b, p);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
