package br.com.controlefinancas.nota;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
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

import org.json.JSONTokener;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abre a nota fiscal (NFC-e) num navegador interno do próprio celular, lê o texto da página
 * e entrega ao app Controle de Finanças, que abre o Novo lançamento já preenchido.
 */
public class MainActivity extends Activity {

    private static final Pattern ENDERECO = Pattern.compile("https?://\\S+");
    private static final Pattern NOTA = Pattern.compile("valor a pagar|vl\\.? ?total|qtd\\. total", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TENTATIVAS = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private LinearLayout painel;
    private TextView status;
    private ProgressBar progresso;
    private Button botaoNavegador;
    private Button botaoPagina;
    private String alvo;
    private int tentativas = 0;
    private boolean enviado = false;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);

        FrameLayout raiz = new FrameLayout(this);
        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(-1, -1));

        painel = new LinearLayout(this);
        painel.setOrientation(LinearLayout.VERTICAL);
        painel.setGravity(Gravity.CENTER);
        painel.setBackgroundColor(Color.WHITE);
        painel.setPadding(dp(24), dp(24), dp(24), dp(24));
        raiz.addView(painel, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.parseColor("#0f172a"));
        painel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progresso = new ProgressBar(this);
        painel.addView(progresso, new LinearLayout.LayoutParams(-2, -2));

        botaoNavegador = botao("Abrir no navegador", v -> abrirNoNavegador());
        botaoPagina = botao("Ver a página", v -> painel.setVisibility(View.GONE));
        setContentView(raiz);

        alvo = descobrirEndereco(getIntent());
        if (alvo == null) {
            mostrar("Nota Fiscal\n\nLeia o QR Code do cupom com a câmera do celular e toque no link. Ou, com a nota aberta no navegador, toque em Compartilhar e escolha Nota Fiscal.", false);
            return;
        }

        mostrar("Lendo a nota…", true);
        WebSettings ajustes = web.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);
        // parece o Chrome comum do celular
        ajustes.setUserAgentString(ajustes.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest pedido, WebResourceError erro) {
                if (pedido.isForMainFrame()) {
                    falhar("Não consegui abrir a página da nota. Verifique a internet.");
                }
            }
        });
        web.loadUrl(alvo);
        handler.postDelayed(this::verificar, 2500);
    }

    /** Pergunta à página o texto que ela mostra; se já for a nota, entrega ao app. Tenta de novo até a página carregar. */
    private void verificar() {
        if (enviado || isFinishing()) return;
        web.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()", valor -> {
            String texto = decodificar(valor);
            if (texto.length() > 150 && NOTA.matcher(texto).find()) {
                enviar(texto);
                return;
            }
            if (texto.toLowerCase().contains("request rejected")) {
                falhar("A Sefaz recusou a consulta.");
                return;
            }
            tentativas++;
            if (tentativas >= MAX_TENTATIVAS) {
                falhar("Não consegui ler a nota.");
                return;
            }
            handler.postDelayed(this::verificar, 1500);
        });
    }

    private void enviar(String texto) {
        if (enviado) return;
        enviado = true;
        String url = getString(R.string.app_url) + "/compartilhar#text=" + Uri.encode(texto);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            enviado = false;
            falhar("Não consegui abrir o app Controle de Finanças.");
            return;
        }
        finish();
    }

    private void falhar(String mensagem) {
        if (enviado) return;
        mostrar(mensagem, false);
        botaoNavegador.setVisibility(View.VISIBLE);
        botaoPagina.setVisibility(View.VISIBLE);
    }

    private void mostrar(String mensagem, boolean carregando) {
        painel.setVisibility(View.VISIBLE);
        status.setText(mensagem);
        progresso.setVisibility(carregando ? View.VISIBLE : View.GONE);
        botaoNavegador.setVisibility(View.GONE);
        botaoPagina.setVisibility(View.GONE);
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
        super.onDestroy();
    }
}
