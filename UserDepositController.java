package com.investments.controller.user.deposit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.investments.component.response.ResponseModel;
import com.investments.model.admin.gateway.type.AdminGatewayType;
import com.investments.model.user.transactions.UserTransactionState;
import com.investments.model.user.transactions.method.UserTransactionMethod;
import com.investments.model.user.User;
import com.investments.model.user.transactions.deposit.UserDeposit;
import com.investments.service.admin.AdminService;
import com.investments.service.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;


@RestController
@RequestMapping("/v1/user/deposit")
public class UserDepositController {

    @Autowired
    private UserService userService;

    @Autowired
    private AdminService adminService;

    @PostMapping("/{value}")
    public String createPayment(@PathVariable("value") double value, @AuthenticationPrincipal UserDetails userDetails) {
        var user = userService.find(userDetails.getUsername());

        if (user == null) {
            return ResponseModel.builder()
                    .error(true)
                    .message("User not found")
                    .build().toJson();
        }

        var config = adminService.getConfiguration();

        if (value < config.getMinDeposit()) {
            return ResponseModel.builder()
                    .error(true)
                    .message("Minimum deposit amount is " + config.getMinDeposit())
                    .build().toJson();
        }

        var userDeposit = userService.createDeposit(user.getEmail(), value);

        var response = new JsonObject();

        if (userDeposit == null) {
            return ResponseModel.builder()
                    .error(true)
                    .message("Ocorreu um erro ao criar o depósito, tente novamente.")
                    .build().toJson();
        }

        response.addProperty("id", userDeposit.getId());
        response.addProperty("qrcode", userDeposit.getQrcode());
        response.addProperty("value", userDeposit.getValue());
        response.addProperty("state", userDeposit.getState().name());
        response.addProperty("createdAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(userDeposit.getCreatedAt()));

        return ResponseModel.builder()
                .error(false)
                .message("QRCode created")
                .data(response)
                .build().toJson();
    }

    @GetMapping("/check/{id}")
    public String check(@PathVariable("id") String id, @AuthenticationPrincipal UserDetails userDetails) {
        var user = userService.find(userDetails.getUsername());

        if (user == null) {
            return ResponseModel.builder()
                    .error(true)
                    .message("User not found")
                    .build().toJson();
        }

        var userDeposit = user.getDeposits().stream().filter(deposit -> deposit.getId().equals(id)).findFirst().orElse(null);

        if (userDeposit == null) {
            return ResponseModel.builder()
                    .error(true)
                    .message("Deposit not found")
                    .build().toJson();
        }

        return ResponseModel.builder()
                .error(false)
                .message("Deposit found")
                .data(userDeposit.getState().name())
                .build().toJson();
    }

    @PostMapping("/callback/{PAYER_ID}/{PAYMENT_METHOD}/{PAYMENT_ID}")
    public String paymentCallback(@RequestBody String req, @PathVariable("PAYER_ID") String id, @PathVariable("PAYMENT_METHOD") String paymentMethod, @PathVariable("PAYMENT_ID") String paymentId) {
        var method = UserTransactionMethod.fromString(paymentMethod);

        System.out.println(req);
        if (method == UserTransactionMethod.ORBITA_PAY) {
            var body = JsonParser.parseString(req).getAsJsonObject();
            if (body.has("data")) {
                var status = body.get("data").getAsJsonObject().get("status").getAsString();

                var userDeposit = userService.updateDeposit(id, paymentId, status.equalsIgnoreCase("PAID") ? UserTransactionState.APPROVED : UserTransactionState.REJECTED);

                if (userDeposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }

                return ResponseModel.builder()
                        .error(false)
                        .message("Deposit updated")
                        .build().toJson();
            } else {
                return ResponseModel.builder()
                        .error(true)
                        .message("Invalid request")
                        .build().toJson();
            }
        } else if (method == UserTransactionMethod.DIGITO_PAY) {
            var body = JsonParser.parseString(req).getAsJsonObject();
            if (body.get("status").getAsString().equalsIgnoreCase("REALIZADO")) {
                var userDeposit = userService.updateDeposit(id, paymentId, UserTransactionState.APPROVED);

                if (userDeposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }

                return ResponseModel.builder()
                        .error(false)
                        .message("Deposit updated")
                        .build().toJson();
            }
        } else if (method == UserTransactionMethod.GATEMAX) {
            if (req.contains("paid")) {
                var userDeposit = userService.updateDeposit(id, paymentId, UserTransactionState.APPROVED);

                if (userDeposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }

                return ResponseModel.builder()
                        .error(false)
                        .message("Deposit updated")
                        .build().toJson();
            }
        } else if (method == UserTransactionMethod.VIZZION_PAY) {
            var body = JsonParser.parseString(req).getAsJsonObject();

            System.out.println(body.toString());

            if (body.has("event")) {
                var transaction = body.get("event").getAsString();

                if (transaction.equalsIgnoreCase("TRANSACTION_PAID")) {
                    var userDeposit = userService.updateDeposit(id, paymentId, UserTransactionState.APPROVED);

                    if (userDeposit == null) {
                        return ResponseModel.builder()
                                .error(true)
                                .message("Error updating deposit")
                                .build().toJson();
                    }

                    System.out.println("Deposit updated paid: " + userDeposit.getId());

                    return ResponseModel.builder()
                            .error(false)
                            .message("Deposit updated")
                            .build().toJson();
                } else {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Payment not completed")
                            .build().toJson();
                }
            } else {
                return ResponseModel.builder()
                        .error(true)
                        .message("Invalid request")
                        .build().toJson();
            }
        } else {
            var body = JsonParser.parseString(req).getAsJsonObject();

            var status = body.get("status").getAsString();

            if (status.equalsIgnoreCase("COMPLETED")) {
                var userDeposit = userService.updateDeposit(id, paymentId, UserTransactionState.APPROVED);

                if (userDeposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }

                return ResponseModel.builder()
                        .error(false)
                        .message("Deposit updated")
                        .build().toJson();
            }
        }

        return ResponseModel.builder()
                .error(true)
                .message("Invalid payment method")
                .build().toJson();
    }

    @PostMapping("/callback/{PAYMENT_METHOD}")
    public String paymentCallback(@RequestBody String req, @PathVariable("PAYMENT_METHOD") String method) {
        var mthd = AdminGatewayType.fromString(method);

        if (mthd != AdminGatewayType.ONNIX_PAY && mthd != AdminGatewayType.PRIMEPAG && mthd != AdminGatewayType.LYTRONPAY && mthd != AdminGatewayType.VALUATION) {
            return ResponseModel.builder()
                    .error(true)
                    .message("Invalid payment method")
                    .build().toJson();
        }

        var body = JsonParser.parseString(req).getAsJsonObject();

        System.out.println(body);

        switch (mthd) {
            case ONNIX_PAY:
                var entity = body.get("entity").getAsString();

                if (!entity.equals("INVOICE")) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Invalid entity")
                            .build().toJson();
                }

                var ref = body.get("reference").getAsString();

                var user = userService.findByReference(ref);

                if (user == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("User not found")
                            .build().toJson();
                }

                var deposit = user.getDeposits().stream().filter(d -> d.getReference() != null && d.getReference().equals(ref)).findFirst().orElse(null);

                if (deposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Deposit not found")
                            .build().toJson();
                }

                var userDeposit = userService.updateDeposit(user.getId(), deposit.getId(), UserTransactionState.APPROVED);

                if (userDeposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }
                break;
            case PRIMEPAG:
                var message = body.get("message").getAsJsonObject();

                var status = message.get("status").getAsString();
                ref = message.get("reference_code").getAsString();

                user = userService.findByReference(ref);

                if (user == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("User not found")
                            .build().toJson();
                }

                deposit = user.getDeposits().stream().filter(d -> d.getReference() != null && d.getReference().equals(ref)).findFirst().orElse(null);

                if (deposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Deposit not found")
                            .build().toJson();
                }

                userDeposit = userService.updateDeposit(user.getId(), deposit.getId(), (status.equalsIgnoreCase("paid") ? UserTransactionState.APPROVED : UserTransactionState.REJECTED));

                if (userDeposit == null) {
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }
                break;
            case LYTRONPAY:
                System.out.println("[LYTRONPAY][Callback] Body: " + body.toString());
                var event = body.has("event") ? body.get("event").getAsString() : "";
                var txid = body.has("txid") ? body.get("txid").getAsString() : "";
                var st = body.has("status") ? body.get("status").getAsString() : "";
                var amount = body.has("amount") ? body.get("amount").getAsDouble() : -1D;
                System.out.println("[LYTRONPAY][Callback] event=" + event + " txid=" + txid + " status=" + st + " amount=" + amount);

                if (!event.equalsIgnoreCase("charge.paid")) {
                    System.out.println("[LYTRONPAY][Callback] Invalid event: " + event);
                    return ResponseModel.builder()
                            .error(true)
                            .message("Invalid event")
                            .build().toJson();
                }

                if (txid.isEmpty()) {
                    System.out.println("[LYTRONPAY][Callback] Missing txid");
                    return ResponseModel.builder()
                            .error(true)
                            .message("Missing txid")
                            .build().toJson();
                }

                var lyUser = userService.findByReference(txid);
                if (lyUser == null) {
                    System.out.println("[LYTRONPAY][Callback] User not found by reference " + txid);
                    return ResponseModel.builder()
                            .error(true)
                            .message("User not found")
                            .build().toJson();
                }
                System.out.println("[LYTRONPAY][Callback] User found: " + lyUser.getId() + " " + lyUser.getEmail());

                var lyDeposit = lyUser.getDeposits().stream()
                        .filter(d -> d.getReference() != null && d.getReference().equals(txid))
                        .findFirst()
                        .orElse(null);

                if (lyDeposit == null) {
                    System.out.println("[LYTRONPAY][Callback] Deposit not found for reference " + txid);
                    return ResponseModel.builder()
                            .error(true)
                            .message("Deposit not found")
                            .build().toJson();
                }
                System.out.println("[LYTRONPAY][Callback] Deposit found: id=" + lyDeposit.getId() + " value=" + lyDeposit.getValue() + " state=" + lyDeposit.getState());

                var lyUpdated = userService.updateDeposit(lyUser.getId(), lyDeposit.getId(),
                        (st.equalsIgnoreCase("pago") || st.equalsIgnoreCase("paid")) ? UserTransactionState.APPROVED : UserTransactionState.REJECTED);

                if (lyUpdated == null) {
                    System.out.println("[LYTRONPAY][Callback] Error updating deposit " + lyDeposit.getId());
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }
                System.out.println("[LYTRONPAY][Callback] Deposit updated: id=" + lyUpdated.getId() + " newState=" + lyUpdated.getState());
                break;
            case VALUATION:
                System.out.println("[VALUATION][Callback] Body: " + body.toString());
                if (!body.has("data") || !body.get("data").isJsonObject()) {
                    System.out.println("[VALUATION][Callback] Missing data object");
                    return ResponseModel.builder()
                            .error(true)
                            .message("Missing data object")
                            .build().toJson();
                }

                var valData = body.getAsJsonObject("data");
                var valStatus = valData.has("status") ? valData.get("status").getAsString() : "";
                var valExternalRef = valData.has("externalRef") && !valData.get("externalRef").isJsonNull() ? valData.get("externalRef").getAsString() : "";
                var valTxId = valData.has("id") ? valData.get("id").getAsString() : "";

                System.out.println("[VALUATION][Callback] status=" + valStatus + ", externalRef=" + valExternalRef + ", id=" + valTxId);

                User valUser = null;
                UserDeposit valDeposit = null;

                if (!valExternalRef.isEmpty()) {
                    valUser = userService.findDeposit(valExternalRef);
                    if (valUser != null) {
                        valDeposit = valUser.getDeposits().stream()
                                .filter(d -> d.getId().equals(valExternalRef))
                                .findFirst()
                                .orElse(null);
                    }
                }

                if (valDeposit == null && !valTxId.isEmpty()) {
                    valUser = userService.findByReference(valTxId);
                    if (valUser != null) {
                        valDeposit = valUser.getDeposits().stream()
                                .filter(d -> d.getReference() != null && d.getReference().equals(valTxId))
                                .findFirst()
                                .orElse(null);
                    }
                }

                if (valUser == null || valDeposit == null) {
                    System.out.println("[VALUATION][Callback] Deposit not found. externalRef=" + valExternalRef + ", txId=" + valTxId);
                    return ResponseModel.builder()
                            .error(true)
                            .message("Deposit not found")
                            .build().toJson();
                }

                UserTransactionState targetState = valStatus.equalsIgnoreCase("paid")
                        ? UserTransactionState.APPROVED
                        : (valStatus.equalsIgnoreCase("refused") || valStatus.equalsIgnoreCase("cancelled")
                                ? UserTransactionState.REJECTED
                                : valDeposit.getState());

                var valUpdated = userService.updateDeposit(valUser.getId(), valDeposit.getId(), targetState);
                if (valUpdated == null) {
                    System.out.println("[VALUATION][Callback] Error updating deposit: " + valDeposit.getId());
                    return ResponseModel.builder()
                            .error(true)
                            .message("Error updating deposit")
                            .build().toJson();
                }
                System.out.println("[VALUATION][Callback] Deposit updated successfully: id=" + valUpdated.getId() + ", state=" + valUpdated.getState());
                break;
            default:
                return ResponseModel.builder()
                        .error(true)
                        .message("Invalid payment method")
                        .build().toJson();
        }

        return ResponseModel.builder()
                .error(false)
                .message("Deposit updated")
                .build().toJson();
    }
}
