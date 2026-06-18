package org.shiroumi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.shiroumi.cli.commands.AgentEntryBackfillCommand
import org.shiroumi.cli.commands.BacktestCommand
import org.shiroumi.cli.commands.BatchAgentDriverCommand
import org.shiroumi.cli.commands.EmotionCommand
import org.shiroumi.cli.commands.GetCandlesCommand
import org.shiroumi.cli.commands.GetIntradayCommand

class QuantCli : CliktCommand(name = "quant-cli", help = "Local IPC CLI tool for Quant KMP Server") {
    override fun run() {
        // Global logic can be added here if needed
    }
}

fun main(args: Array<String>) {
    QuantCli()
        .subcommands(
            EmotionCommand(),
            GetCandlesCommand(),
            GetIntradayCommand(),
            BacktestCommand(),
            BatchAgentDriverCommand(),
            AgentEntryBackfillCommand(),
        )
        .main(args)
}

