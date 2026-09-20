package me.mycellium.skymyce.commands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.teamresourceful.resourcefulconfig.api.client.ResourcefulConfigScreen
import me.mycellium.skymyce.ModuleManager
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.features.general.AuctionHouseScreen
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonScreen
import me.mycellium.skymyce.features.instances.dungeons.RunSplitsScreen
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.Utils.displayMessage
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource


object SkyMyceCommands : SkyMyceModule() {
    override fun init() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = dispatcher.register(root())
            dispatcher.register(
                literal("sm").redirect(root)
            )
        }
    }

    fun root(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("skymyce")
            .executes {
                MC.instance.schedule {
                    MC.instance.setScreen(
                        ResourcefulConfigScreen.make(SkyMyce.config).build()
                    )
                }
                1
            }
            .then(dungeon())
            .then(splits())
            .then(auction())
            .then(hud())
            .then(modules())
            .then(test())
            .then(troll())
    }

    fun test(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("ah_data")
            .executes {
                AuctionAPI.getData()
                1
            }
    }

    fun dungeon(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("dungeon")
            .executes {
                MC.instance.execute {
                    MC.instance.setScreen(DungeonScreen())
                }
                1
            }
    }

    fun splits(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("splits")
            .executes {
                MC.instance.execute {
                    MC.instance.setScreen(RunSplitsScreen())
                }
                1
            }
    }

    fun auction(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("ah")
            .executes {
                MC.instance.execute {
                    MC.instance.setScreen(AuctionHouseScreen())
                }

                fun splits(): LiteralArgumentBuilder<FabricClientCommandSource> {
                    return literal("splits")
                        .executes {
                            MC.instance.execute {
                                MC.instance.setScreen(RunSplitsScreen())
                            }
                            1
                        }
                }
                1
            }
    }

    fun hud(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("hud")
            .executes {
                MC.instance.execute {
                    MC.instance.setScreen(WidgetEditorScreen)
                }
                1
            }
    }

    fun modules(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("modules")
            .executes {
                displayMessage("§b§l--= Modules: ${ModuleManager.modules.size} =--")
                ModuleManager.modules.forEach  {
                    displayMessage("§${if (it.isEnabled()) "a✔" else "c❌"} ${it.javaClass.simpleName}")
                }
                1
            }
    }
    var trueProfit = false
    fun troll(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("calc")
            .executes {
                trueProfit =! trueProfit
                displayMessage(string = "[SM]: i calculate nothing")
                1
            }
    }
}
