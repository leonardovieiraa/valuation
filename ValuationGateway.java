package com.investments.component.gateways;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.investments.App;
import com.investments.model.admin.gateway.type.AdminGatewayType;
import com.investments.service.admin.AdminService;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class ValuationGateway {

    @Value("${endpoint-backend}")
    private String endpointBackend;

    private final String url = "https://valuationapp.site/api/v1";

    @Autowired
    private AdminService adminService;

    private String getAuthCredentials() {
        var gateway = adminService.getConfiguration().getGateway();
        if (gateway == null) {
            return "";
        }
        var pub = gateway.getValuationPublicKey();
        var priv = gateway.getValuationPrivateKey();

        if (pub == null || pub.isBlank()) {
            pub = gateway.getApiToken();
        }
        if (priv == null || priv.isBlank()) {
            priv = gateway.getApiSecret();
        }

        var credentials = (pub != null ? pub.trim() : "") + ":" + (priv != null ? priv.trim() : "");
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String getWithdrawKey() {
        var gateway = adminService.getConfiguration().getGateway();
        if (gateway == null) {
            return "";
        }
        var key = gateway.getValuationWithdrawKey();
        if (key == null || key.isBlank()) {
            key = gateway.getApiSecret();
        }
        return key != null ? key.trim() : "";
    }

    public JsonObject createPayment(String orderId, String cpf, String name, String email, String phone, Double value) {
        long amountInCents = Math.round(value * 100);

        var payment = new JsonObject();
        payment.addProperty("amount", amountInCents);
        payment.addProperty("paymentMethod", "pix");
        payment.addProperty("postbackUrl", endpointBackend + "/v1/user/deposit/callback/" + AdminGatewayType.VALUATION.name());
        payment.addProperty("externalRef", orderId);

        var customer = new JsonObject();
        customer.addProperty("name", (name != null && !name.isBlank()) ? name : "Cliente");
        
        String cleanEmail = (email != null && !email.isBlank()) ? email : 
                ((name != null && !name.isBlank()) ? name.replaceAll("\\s+", "").toLowerCase() + "@ativoeasy.com" : "cliente@ativoeasy.com");
        customer.addProperty("email", cleanEmail);

        String cleanPhone = (phone != null && !phone.isBlank()) ? phone.replaceAll("\\D", "") : "11999999999";
        if (cleanPhone.length() < 10) {
            cleanPhone = "11999999999";
        }
        customer.addProperty("phone", cleanPhone);

        var document = new JsonObject();
        document.addProperty("type", "cpf");
        document.addProperty("number", cpf != null ? cpf.replaceAll("\\D", "") : "");
        customer.add("document", document);

        payment.add("customer", customer);

        var items = new JsonArray();
        var item = new JsonObject();
        item.addProperty("title", "Depósito #" + orderId);
        item.addProperty("quantity", 1);
        item.addProperty("unitPrice", amountInCents);
        item.addProperty("tangible", false);
        items.add(item);

        payment.add("items", items);

        var body = RequestBody.create(MediaType.parse("application/json"), payment.toString());

        var request = new Request.Builder()
                .url(url + "/transactions")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", getAuthCredentials())
                .build();

        try (var response = App.getOkHttpClient().newCall(request).execute()) {
            if (response.body() == null) {
                System.out.println("[ValuationGateway][createPayment] Response body is null");
                return null;
            }
            var responseStr = response.body().string();
            System.out.println("[ValuationGateway][createPayment] Status: " + response.code() + ", Body: " + responseStr);
            return JsonParser.parseString(responseStr).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public JsonObject createWithdrawal(String recipientName, String pixKeyType, String pixKey, Double value) {
        long amountInCents = Math.round(value * 100);

        var withdrawal = new JsonObject();
        withdrawal.addProperty("amount", amountInCents);
        withdrawal.addProperty("recipientName", (recipientName != null && !recipientName.isBlank()) ? recipientName : "Beneficiário");

        String normalizedType = normalizePixKeyType(pixKeyType);
        withdrawal.addProperty("pixKeyType", normalizedType);

        String cleanKey = pixKey != null ? pixKey.trim() : "";
        if ("cpf".equalsIgnoreCase(normalizedType) || "cnpj".equalsIgnoreCase(normalizedType)) {
            cleanKey = cleanKey.replaceAll("\\D", "");
        }
        withdrawal.addProperty("pixKey", cleanKey);

        var body = RequestBody.create(MediaType.parse("application/json"), withdrawal.toString());

        var request = new Request.Builder()
                .url(url + "/withdrawals")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", getAuthCredentials())
                .addHeader("x-withdraw-key", getWithdrawKey())
                .build();

        try (var response = App.getOkHttpClient().newCall(request).execute()) {
            if (response.body() == null) {
                System.out.println("[ValuationGateway][createWithdrawal] Response body is null");
                return null;
            }
            var responseStr = response.body().string();
            System.out.println("[ValuationGateway][createWithdrawal] Status: " + response.code() + ", Body: " + responseStr);
            return JsonParser.parseString(responseStr).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public JsonObject getBalance() {
        var request = new Request.Builder()
                .url(url + "/balance/available")
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", getAuthCredentials())
                .build();

        try (var response = App.getOkHttpClient().newCall(request).execute()) {
            if (response.body() == null) return null;
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public JsonObject getTransaction(String id) {
        var request = new Request.Builder()
                .url(url + "/transactions/" + id)
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", getAuthCredentials())
                .build();

        try (var response = App.getOkHttpClient().newCall(request).execute()) {
            if (response.body() == null) return null;
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public JsonObject getWithdrawal(String id) {
        var request = new Request.Builder()
                .url(url + "/withdrawals/" + id)
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", getAuthCredentials())
                .build();

        try (var response = App.getOkHttpClient().newCall(request).execute()) {
            if (response.body() == null) return null;
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String normalizePixKeyType(String pixKeyType) {
        if (pixKeyType == null || pixKeyType.isBlank()) {
            return "cpf";
        }
        String lower = pixKeyType.toLowerCase().trim();
        return switch (lower) {
            case "cpf" -> "cpf";
            case "cnpj" -> "cnpj";
            case "phone", "telefone", "celular" -> "phone";
            case "email", "e-mail" -> "email";
            case "copypaste", "copia_e_cola" -> "copypaste";
            case "random", "aleatoria", "chave_aleatoria", "evp" -> "random";
            default -> lower;
        };
    }
}
