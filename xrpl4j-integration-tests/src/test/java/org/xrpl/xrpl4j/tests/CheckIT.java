package org.xrpl.xrpl4j.tests;

/*-
 * ========================LICENSE_START=================================
 * xrpl4j :: integration-tests
 * %%
 * Copyright (C) 2020 - 2022 XRPL Foundation and its contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.hash.Hashing;
import com.google.common.primitives.UnsignedInteger;
import org.junit.jupiter.api.Test;
import org.xrpl.xrpl4j.client.JsonRpcClientErrorException;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoResult;
import org.xrpl.xrpl4j.model.client.fees.FeeResult;
import org.xrpl.xrpl4j.model.client.fees.FeeUtils;
import org.xrpl.xrpl4j.model.client.transactions.SubmitResult;
import org.xrpl.xrpl4j.model.ledger.CheckObject;
import org.xrpl.xrpl4j.model.ledger.LedgerObject;
import org.xrpl.xrpl4j.model.transactions.CheckCancel;
import org.xrpl.xrpl4j.model.transactions.CheckCash;
import org.xrpl.xrpl4j.model.transactions.CheckCreate;
import org.xrpl.xrpl4j.model.transactions.Hash256;
import org.xrpl.xrpl4j.model.transactions.TransactionResultCodes;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;
import org.xrpl.xrpl4j.wallet.Wallet;

import java.util.function.Predicate;

public class CheckIT extends AbstractIT {

  @Test
  public void createXrpCheckAndCash() throws JsonRpcClientErrorException {

    //////////////////////
    // Generate and fund source and destination accounts
    Wallet sourceWallet = createRandomAccount();
    Wallet destinationWallet = createRandomAccount();

    FeeResult feeResult = xrplClient.fee();
    AccountInfoResult accountInfoResult = this.scanForResult(
      () -> this.getValidatedAccountInfo(sourceWallet.classicAddress())
    );

    //////////////////////
    // Create a Check with an InvoiceID for easy identification
    Hash256 invoiceId = Hash256.of(Hashing.sha256().hashBytes("Check this out.".getBytes()).toString());
    CheckCreate checkCreate = CheckCreate.builder()
      .account(sourceWallet.classicAddress())
      .fee(FeeUtils.computeNetworkFees(feeResult).recommendedFee())
      .sequence(accountInfoResult.accountData().sequence())
      .destination(destinationWallet.classicAddress())
      .sendMax(XrpCurrencyAmount.ofDrops(12345))
      .invoiceId(invoiceId)
      .signingPublicKey(sourceWallet.publicKey())
      .build();

    SubmitResult<CheckCreate> response = xrplClient.submit(sourceWallet, checkCreate);
    assertThat(response.result()).isEqualTo(TransactionResultCodes.TES_SUCCESS);
    assertThat(response.transactionResult().transaction().hash()).isNotEmpty().get()
      .isEqualTo(response.transactionResult().hash());

    logInfo(
      response.transactionResult().transaction().transactionType(),
      response.transactionResult().hash()
    );

    //////////////////////
    // Poll the ledger for the source wallet's account objects, and validate that the created Check makes
    // it into the ledger
    CheckObject checkObject = (CheckObject) this.scanForResult(
      () -> this.getValidatedAccountObjects(sourceWallet.classicAddress()),
      result -> result.accountObjects().stream().anyMatch(findCheck(sourceWallet, destinationWallet, invoiceId))
    )
      .accountObjects().stream()
      .filter(findCheck(sourceWallet, destinationWallet, invoiceId))
      .findFirst().get();

    //////////////////////
    // Destination wallet cashes the Check
    feeResult = xrplClient.fee();
    AccountInfoResult destinationAccountInfo = this.scanForResult(
      () -> this.getValidatedAccountInfo(destinationWallet.classicAddress())
    );
    CheckCash checkCash = CheckCash.builder()
      .account(destinationWallet.classicAddress())
      .amount(checkObject.sendMax())
      .sequence(destinationAccountInfo.accountData().sequence())
      .fee(FeeUtils.computeNetworkFees(feeResult).recommendedFee())
      .checkId(checkObject.index())
      .signingPublicKey(destinationWallet.publicKey())
      .build();
    SubmitResult<CheckCash> cashResponse = xrplClient.submit(destinationWallet, checkCash);
    assertThat(cashResponse.result()).isEqualTo(TransactionResultCodes.TES_SUCCESS);
    assertThat(response.transactionResult().transaction().hash()).isNotEmpty().get()
      .isEqualTo(response.transactionResult().hash());
    logInfo(
      cashResponse.transactionResult().transaction().transactionType(),
      cashResponse.transactionResult().hash()
    );

    //////////////////////
    // Validate that the destination account balance increases by the check amount minus fees
    this.scanForResult(
      () -> this.getValidatedAccountInfo(destinationWallet.classicAddress()),
      result -> {
        logger.info("AccountInfoResult after CheckCash balance: {}", result.accountData().balance().value());
        return result.accountData().balance().equals(
          destinationAccountInfo.accountData().balance()
            .plus((XrpCurrencyAmount) checkObject.sendMax())
            .minus(checkCash.fee()));
      });

    //////////////////////
    // Validate that the Check object was deleted
    this.scanForResult(
      () -> this.getValidatedAccountObjects(sourceWallet.classicAddress()),
      result -> result.accountObjects().stream().noneMatch(findCheck(sourceWallet, destinationWallet, invoiceId))
    );
  }

  @Test
  public void createCheckAndSourceCancels() throws JsonRpcClientErrorException {

    //////////////////////
    // Generate and fund source and destination accounts
    Wallet sourceWallet = createRandomAccount();
    Wallet destinationWallet = createRandomAccount();

    //////////////////////
    // Create a Check with an InvoiceID for easy identification
    FeeResult feeResult = xrplClient.fee();
    AccountInfoResult accountInfoResult = this.scanForResult(
      () -> this.getValidatedAccountInfo(sourceWallet.classicAddress())
    );

    Hash256 invoiceId = Hash256.of(Hashing.sha256().hashBytes("Check this out.".getBytes()).toString());
    CheckCreate checkCreate = CheckCreate.builder()
      .account(sourceWallet.classicAddress())
      .fee(FeeUtils.computeNetworkFees(feeResult).recommendedFee())
      .sequence(accountInfoResult.accountData().sequence())
      .destination(destinationWallet.classicAddress())
      .sendMax(XrpCurrencyAmount.ofDrops(12345))
      .invoiceId(invoiceId)
      .signingPublicKey(sourceWallet.publicKey())
      .build();

    SubmitResult<CheckCreate> response = xrplClient.submit(sourceWallet, checkCreate);
    assertThat(response.result()).isEqualTo(TransactionResultCodes.TES_SUCCESS);
    assertThat(response.transactionResult().transaction().hash()).isNotEmpty().get()
      .isEqualTo(response.transactionResult().hash());

    logInfo(
      response.transactionResult().transaction().transactionType(),
      response.transactionResult().hash()
    );

    //////////////////////
    // Poll the ledger for the source wallet's account objects, and validate that the created Check makes
    // it into the ledger
    CheckObject checkObject = (CheckObject) this.scanForResult(
      () -> this.getValidatedAccountObjects(sourceWallet.classicAddress()),
      result -> result.accountObjects().stream().anyMatch(findCheck(sourceWallet, destinationWallet, invoiceId))
    )
      .accountObjects().stream()
      .filter(findCheck(sourceWallet, destinationWallet, invoiceId))
      .findFirst().get();

    //////////////////////
    // Source account cancels the Check
    feeResult = xrplClient.fee();
    CheckCancel checkCancel = CheckCancel.builder()
      .account(sourceWallet.classicAddress())
      .sequence(accountInfoResult.accountData().sequence().plus(UnsignedInteger.ONE))
      .fee(FeeUtils.computeNetworkFees(feeResult).recommendedFee())
      .checkId(checkObject.index())
      .signingPublicKey(sourceWallet.publicKey())
      .build();

    SubmitResult<CheckCancel> cancelResult = xrplClient.submit(sourceWallet, checkCancel);
    assertThat(cancelResult.result()).isEqualTo(TransactionResultCodes.TES_SUCCESS);
    assertThat(response.transactionResult().transaction().hash()).isNotEmpty().get()
      .isEqualTo(response.transactionResult().hash());

    logInfo(
      cancelResult.transactionResult().transaction().transactionType(),
      cancelResult.transactionResult().hash()
    );

    //////////////////////
    // Validate that the Check does not exist after cancelling
    this.scanForResult(
      () -> this.getValidatedAccountObjects(sourceWallet.classicAddress()),
      result -> result.accountObjects().stream().noneMatch(findCheck(sourceWallet, destinationWallet, invoiceId))
    );
  }

  @Test
  public void createCheckAndDestinationCancels() throws JsonRpcClientErrorException {

    //////////////////////
    // Generate and fund source and destination accounts
    Wallet sourceWallet = createRandomAccount();
    Wallet destinationWallet = createRandomAccount();

    //////////////////////
    // Create a Check with an InvoiceID for easy identification
    FeeResult feeResult = xrplClient.fee();
    AccountInfoResult accountInfoResult = this.scanForResult(
      () -> this.getValidatedAccountInfo(sourceWallet.classicAddress())
    );

    Hash256 invoiceId = Hash256.of(Hashing.sha256().hashBytes("Check this out.".getBytes()).toString());
    CheckCreate checkCreate = CheckCreate.builder()
      .account(sourceWallet.classicAddress())
      .fee(FeeUtils.computeNetworkFees(feeResult).recommendedFee())
      .sequence(accountInfoResult.accountData().sequence())
      .destination(destinationWallet.classicAddress())
      .sendMax(XrpCurrencyAmount.ofDrops(12345))
      .invoiceId(invoiceId)
      .signingPublicKey(sourceWallet.publicKey())
      .build();

    //////////////////////
    // Poll the ledger for the source wallet's account objects, and validate that the created Check makes
    // it into the ledger
    SubmitResult<CheckCreate> response = xrplClient.submit(sourceWallet, checkCreate);
    assertThat(response.result()).isEqualTo(TransactionResultCodes.TES_SUCCESS);
    assertThat(response.transactionResult().transaction().hash()).isNotEmpty().get()
      .isEqualTo(response.transactionResult().hash());

    logInfo(
      response.transactionResult().transaction().transactionType(),
      response.transactionResult().hash()
    );

    CheckObject checkObject = (CheckObject) this.scanForResult(
      () -> this.getValidatedAccountObjects(sourceWallet.classicAddress()),
      result -> result.accountObjects().stream().anyMatch(findCheck(sourceWallet, destinationWallet, invoiceId))
    )
      .accountObjects().stream()
      .filter(findCheck(sourceWallet, destinationWallet, invoiceId))
      .findFirst().get();

    //////////////////////
    // Destination account cancels the Check
    feeResult = xrplClient.fee();
    AccountInfoResult destinationAccountInfo = this.scanForResult(
      () -> this.getValidatedAccountInfo(destinationWallet.classicAddress())
    );
    CheckCancel checkCancel = CheckCancel.builder()
      .account(destinationWallet.classicAddress())
      .sequence(destinationAccountInfo.accountData().sequence())
      .fee(FeeUtils.computeNetworkFees(feeResult).recommendedFee())
      .checkId(checkObject.index())
      .signingPublicKey(destinationWallet.publicKey())
      .build();

    SubmitResult<CheckCancel> cancelResult = xrplClient.submit(destinationWallet, checkCancel);
    assertThat(cancelResult.result()).isEqualTo(TransactionResultCodes.TES_SUCCESS);
    assertThat(response.transactionResult().transaction().hash()).isNotEmpty().get()
      .isEqualTo(response.transactionResult().hash());

    logInfo(
      cancelResult.transactionResult().transaction().transactionType(),
      cancelResult.transactionResult().hash()
    );

    //////////////////////
    // Validate that the Check does not exist after cancelling
    this.scanForResult(
      () -> this.getValidatedAccountObjects(sourceWallet.classicAddress()),
      result -> result.accountObjects().stream().noneMatch(findCheck(sourceWallet, destinationWallet, invoiceId)));
  }

  private Predicate<LedgerObject> findCheck(Wallet sourceWallet, Wallet destinationWallet, Hash256 invoiceId) {
    return object ->
      CheckObject.class.isAssignableFrom(object.getClass()) &&
        ((CheckObject) object).invoiceId().map(id -> id.equals(invoiceId)).orElse(false) &&
        ((CheckObject) object).account().equals(sourceWallet.classicAddress()) &&
        ((CheckObject) object).destination().equals(destinationWallet.classicAddress());
  }

}
