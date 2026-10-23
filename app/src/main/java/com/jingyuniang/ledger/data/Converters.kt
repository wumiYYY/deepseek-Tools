package com.jingyuniang.ledger.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun txnTypeToString(v: TxnType?): String = (v ?: TxnType.EXPENSE).name
    @TypeConverter fun stringToTxnType(v: String?): TxnType =
        runCatching { TxnType.valueOf(v ?: "") }.getOrDefault(TxnType.EXPENSE)

    @TypeConverter fun accountTypeToString(v: AccountType?): String = (v ?: AccountType.OTHER).name
    @TypeConverter fun stringToAccountType(v: String?): AccountType =
        runCatching { AccountType.valueOf(v ?: "") }.getOrDefault(AccountType.OTHER)

    @TypeConverter fun sourceToString(v: TxnSource?): String = (v ?: TxnSource.MANUAL).name
    @TypeConverter fun stringToSource(v: String?): TxnSource =
        runCatching { TxnSource.valueOf(v ?: "") }.getOrDefault(TxnSource.MANUAL)

    @TypeConverter fun captureStatusToString(v: CaptureStatus?): String = (v ?: CaptureStatus.PENDING).name
    @TypeConverter fun stringToCaptureStatus(v: String?): CaptureStatus =
        runCatching { CaptureStatus.valueOf(v ?: "") }.getOrDefault(CaptureStatus.PENDING)

    @TypeConverter fun freqToString(v: Freq?): String = (v ?: Freq.MONTHLY).name
    @TypeConverter fun stringToFreq(v: String?): Freq =
        runCatching { Freq.valueOf(v ?: "") }.getOrDefault(Freq.MONTHLY)

    @TypeConverter fun debtTypeToString(v: DebtType?): String = (v ?: DebtType.RECEIVABLE).name
    @TypeConverter fun stringToDebtType(v: String?): DebtType =
        runCatching { DebtType.valueOf(v ?: "") }.getOrDefault(DebtType.RECEIVABLE)

    @TypeConverter fun reimburseToString(v: ReimburseState?): String = (v ?: ReimburseState.NONE).name
    @TypeConverter fun stringToReimburse(v: String?): ReimburseState =
        runCatching { ReimburseState.valueOf(v ?: "") }.getOrDefault(ReimburseState.NONE)
}
