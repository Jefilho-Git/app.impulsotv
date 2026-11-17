package com.impulsolab.impulsotv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * [KIOSK] Tela inicial (HOME) no modo administrador — invisível.
 *
 * O Android trata a activity de HOME de forma especial (tarefa própria), então
 * apontar o HOME direto para a MainActivity criava uma SEGUNDA cópia do player
 * (áudio e heartbeat em dobro). Esta "ponte" só traz a MainActivity existente
 * para a frente — ou a cria, se não houver — e se fecha.
 *
 * Fica desativada no manifest; o DeviceOwner liga e define como HOME padrão.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
        overridePendingTransition(0, 0)
    }
}
