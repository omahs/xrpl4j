package org.xrpl.xrpl4j.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xrpl.xrpl4j.client.JsonRpcClientErrorException;
import org.xrpl.xrpl4j.model.client.FinalityStatus;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoResult;
import org.xrpl.xrpl4j.model.client.common.LedgerIndex;
import org.xrpl.xrpl4j.model.client.common.LedgerSpecifier;
import org.xrpl.xrpl4j.model.client.fees.FeeResult;
import org.xrpl.xrpl4j.model.client.fees.FeeUtils;
import org.xrpl.xrpl4j.model.client.ledger.LedgerRequestParams;
import org.xrpl.xrpl4j.model.client.transactions.SubmitResult;
import org.xrpl.xrpl4j.model.immutables.FluentCompareTo;
import org.xrpl.xrpl4j.model.transactions.Hash256;
import org.xrpl.xrpl4j.model.transactions.ImmutablePayment;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;
import org.xrpl.xrpl4j.wallet.Wallet;

public class IsFinalIT extends AbstractIT {

  Wallet wallet = createRandomAccount();

  ImmutablePayment.Builder payment;
  UnsignedInteger lastLedgerSequence;
  AccountInfoResult accountInfo;

  @BeforeEach
  void setup() throws JsonRpcClientErrorException {
    ///////////////////////
    // Get validated account info and validate account state
    accountInfo = this.scanForResult(() -> this.getValidatedAccountInfo(wallet.classicAddress()));
    assertThat(accountInfo.status()).isNotEmpty().get().isEqualTo("success");
    assertThat(accountInfo.accountData().flags().lsfGlobalFreeze()).isEqualTo(false);

    FeeResult feeResult = xrplClient.fee();

    LedgerIndex validatedLedger = xrplClient.ledger(
        LedgerRequestParams.builder().ledgerSpecifier(LedgerSpecifier.VALIDATED)
          .build()
      )
      .ledgerIndexSafe();


    lastLedgerSequence = UnsignedInteger.valueOf(
      validatedLedger.plus(UnsignedLong.valueOf(1)).unsignedLongValue().intValue()
    );

    Wallet destinationWallet = createRandomAccount();
    payment = Payment.builder()
      .account(wallet.classicAddress())
      .fee(FeeUtils.computeNetworkFees(feeResult).recommendedFee())
      .sequence(accountInfo.accountData().sequence())
      .destination(destinationWallet.classicAddress())
      .amount(XrpCurrencyAmount.ofDrops(10))
      .signingPublicKey(wallet.publicKey());
  }

  @Test
  public void simpleIsFinalTest() throws JsonRpcClientErrorException {

    Payment builtPayment = payment.build();
    SubmitResult response = xrplClient.submit(wallet, builtPayment);
    assertThat(response.result()).isEqualTo("tesSUCCESS");
    Hash256 txHash = response.transactionResult().hash();

    assertThat(
      xrplClient.isFinal(
        txHash,
        response.validatedLedgerIndex(),
        lastLedgerSequence,
        accountInfo.accountData().sequence(),
        wallet.classicAddress()
      ).finalityStatus()
    ).isEqualTo(FinalityStatus.NOT_FINAL);

    this.scanForResult(
      () -> getValidatedTransaction(txHash, Payment.class)
    );

    assertThat(
      xrplClient.isFinal(
        txHash,
        response.validatedLedgerIndex(),
        lastLedgerSequence,
        accountInfo.accountData().sequence(),
        wallet.classicAddress()
      ).finalityStatus()
    ).isEqualTo(FinalityStatus.VALIDATED_SUCCESS);
  }

  @Test
  public void isFinalExpiredTxTest() throws JsonRpcClientErrorException {

    Payment builtPayment = payment
      .sequence(accountInfo.accountData().sequence().minus(UnsignedInteger.ONE))
      .build();
    SubmitResult response = xrplClient.submit(wallet, builtPayment);
    Hash256 txHash = response.transactionResult().hash();

    assertThat(
      xrplClient.isFinal(
        txHash,
        response.validatedLedgerIndex(),
        lastLedgerSequence.minus(UnsignedInteger.ONE),
        accountInfo.accountData().sequence(),
        wallet.classicAddress()
      ).finalityStatus()
    ).isEqualTo(FinalityStatus.NOT_FINAL);

    this.scanForResult(
      () -> this.getValidatedLedger(),
      ledger -> FluentCompareTo.is(ledger.ledgerIndexSafe().unsignedIntegerValue())
        .greaterThan(lastLedgerSequence.minus(UnsignedInteger.ONE))
    );

    assertThat(
      xrplClient.isFinal(
        txHash,
        response.validatedLedgerIndex(),
        lastLedgerSequence.minus(UnsignedInteger.ONE),
        accountInfo.accountData().sequence(),
        wallet.classicAddress()
      ).finalityStatus()
    ).isEqualTo(FinalityStatus.EXPIRED);
  }

  @Test
  public void isFinalNoTrustlineIouPayment_ValidatedFailureResponse() throws JsonRpcClientErrorException {

    Payment builtPayment = payment
      .amount(IssuedCurrencyAmount.builder().currency("USD").issuer(
        wallet.classicAddress()).value("500").build()
      ).build();
    SubmitResult response = xrplClient.submit(wallet, builtPayment);
    Hash256 txHash = response.transactionResult().hash();

    assertThat(
      xrplClient.isFinal(
        txHash,
        response.validatedLedgerIndex(),
        lastLedgerSequence,
        accountInfo.accountData().sequence(),
        wallet.classicAddress()
      ).finalityStatus()
    ).isEqualTo(FinalityStatus.NOT_FINAL);

    this.scanForResult(
      () -> getValidatedTransaction(txHash, Payment.class)
    );

    assertThat(
      xrplClient.isFinal(
        txHash,
        response.validatedLedgerIndex(),
        lastLedgerSequence,
        accountInfo.accountData().sequence(),
        wallet.classicAddress()
      ).finalityStatus()
    ).isEqualTo(FinalityStatus.VALIDATED_FAILURE);
  }
}
