/*
 * Copyright (C) 2021 Square, Inc.
 *
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
 */
package app.cash.quickjs.ktbridge.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Rewrites calls to `createJsService` with two arguments:
 *
 * ```
 * val helloService = createJsService(EchoJsAdapter, JsEchoService("hello"))
 * ```
 *
 * to the overload that takes three arguments:
 *
 * ```
 * val helloService = createJsService(
 *   jsAdapter = EchoJsAdapter,
 *   service = JsEchoService("hello"),
 *   block = fun (inboundCall: InboundCall<EchoService>): ByteArray {
 *     return when {
 *       inboundCall.funName == "echo" -> {
 *         inboundCall.result(
 *           inboundCall.service.echo(
 *             inboundCall.parameter()
 *           )
 *         )
 *       }
 *       else -> inboundCall.unexpectedFunction()
 *     }
 *   }
 * )
 * ```
 *
 * This synthesizes a function that routes calls by their name, decodes parameters, and encodes the
 * result.
 */
@ObsoleteDescriptorBasedAPI // TODO(jwilson): is there an alternative?
class InboundCallRewriter(
  private val pluginContext: IrPluginContext,
  private val backingField: IrField,
  private val initializer: IrExpressionBody,
  private val initializerCall: IrCall,
) {
  private val irFactory: IrFactory
    get() = pluginContext.irFactory

  private val createJsServiceFunction3Arg = pluginContext.referenceFunctions(CREATE_JS_SERVICE)
    .single { it.descriptor.valueParameters.size == 3 }
  private val classInboundCall = pluginContext.referenceClass(INBOUND_CALL) ?: error("TODO")
  private val serviceInterface = initializerCall.getTypeArgument(0) ?: error("TODO")
  private val inboundCallOfTType = classInboundCall.typeWith(serviceInterface)

  private val function: IrSimpleFunction = irFactory.buildFun {
    name = Name.special("<no name provided>")
    returnType = pluginContext.symbols.byteArrayType
  }.apply {
    parent = backingField
    addValueParameter(
      name = "inboundCall",
      type = inboundCallOfTType,
      origin = IrDeclarationOrigin.DEFINED
    )
  }

  fun rewrite() {
    val irBuilder = DeclarationIrBuilder(
      generatorContext = pluginContext,
      symbol = IrSimpleFunctionSymbolImpl(),
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET
    )

    val iBridgedFunctionsWhen = irBuilder.irBridgedFunctionsWhen()

    function.body = IrBlockBodyImpl(
      startOffset = irBuilder.startOffset,
      endOffset = irBuilder.endOffset,
    ).apply {
      statements += irBuilder.irReturn(iBridgedFunctionsWhen)
    }

    initializer.expression = irCall(initializerCall, createJsServiceFunction3Arg).apply {
      putValueArgument(2, irBuilder.irFunction1Expression())
    }
  }

  /**
   * ```
   * when {
   *   inboundCall.funName == "function1" -> ...
   *   inboundCall.funName == "function2" -> ...
   *   ...
   *   else -> ...
   * }
   * ```
   */
  private fun IrBuilderWithScope.irBridgedFunctionsWhen(): IrExpression {
    val result = mutableListOf<IrBranch>()

    val serviceInterface = pluginContext.referenceClass(serviceInterface.classFqName!!)
      ?: error("TODO")

    // Each bridged function gets its own branch in the when() expression.
    for (bridgedFunction in serviceInterface.functions) {
      // TODO(jwilson): find a better way to skip equals()/hashCode()/toString()
      if (bridgedFunction.owner.isFakeOverride) continue

      result += irBranch(
        condition = irEquals(
          arg1 = irFunName(),
          arg2 = irString(bridgedFunction.descriptor.name.identifier)
        ),
        result = irCallEncodeResult(
          resultExpression = irCallServiceFunction(bridgedFunction)
        )
      )
    }

    // Add an else clause that calls unexpectedFunction().
    result += irBranch(
      condition = irTrue(),
      result = irCallUnexpectedFunction()
    )

    return irWhen(
      type = pluginContext.symbols.byteArrayType,
      branches = result
    )
  }

  /** `inboundCall.funName` */
  private fun IrBuilderWithScope.irFunName(): IrExpression {
    return irCall(
      callee = classInboundCall.getPropertyGetter("funName")!!,
      type = pluginContext.symbols.string.defaultType,
    ).apply {
      dispatchReceiver = irGetInboundCallParameter()
    }
  }

  /** `inboundCall.service.function1(...)` */
  private fun IrBuilderWithScope.irCallServiceFunction(
    bridgedFunction: IrSimpleFunctionSymbol,
  ): IrExpression {
    val getServiceCall = irService()
    val returnType = pluginContext.typeTranslator.translateType(
      bridgedFunction.descriptor.returnType ?: error("TODO")
    )

    return irCall(
      type = returnType,
      callee = bridgedFunction,
      valueArgumentsCount = bridgedFunction.descriptor.valueParameters.size,
    ).apply {
      dispatchReceiver = getServiceCall

      for (p in bridgedFunction.descriptor.valueParameters.indices) {
        putValueArgument(
          p,
          irCallDecodeParameter(
            valueParameter = bridgedFunction.descriptor.valueParameters[p]
          ),
        )
      }
    }
  }

  /** `inboundCall.service` */
  private fun IrBuilderWithScope.irService(): IrExpression {
    return irCall(
      type = serviceInterface,
      callee = classInboundCall.getPropertyGetter("service")!!,
    ).apply {
      dispatchReceiver = irGetInboundCallParameter()
    }
  }

  /** `inboundCall.decode(...)` */
  private fun IrBuilderWithScope.irCallDecodeParameter(
    valueParameter: ValueParameterDescriptor
  ): IrExpression {
    val parameterFunction = pluginContext.referenceFunctions(
      INBOUND_CALL.child(
        Name.identifier("parameter")
      )
    ).single { it.descriptor.isInline }

    val parameterType = pluginContext.typeTranslator.translateType(valueParameter.type)
    return irCall(
      type = parameterType,
      callee = parameterFunction,
      typeArgumentsCount = 1,
    ).apply {
      dispatchReceiver = irGetInboundCallParameter()
      putTypeArgument(0, parameterType)
    }
  }

  /** `inboundCall` */
  private fun IrBuilderWithScope.irGetInboundCallParameter(): IrGetValue {
    return irGet(
      type = inboundCallOfTType,
      variable = function.valueParameters[0].symbol,
    )
  }

  /** `inboundCall.result(...)` */
  private fun IrBuilderWithScope.irCallEncodeResult(
    resultExpression: IrExpression
  ): IrExpression {
    val resultFunction = pluginContext.referenceFunctions(
      INBOUND_CALL.child(Name.identifier("result"))
    ).single { it.descriptor.isInline }

    return irCall(
      type = pluginContext.symbols.byteArrayType,
      callee = resultFunction,
      typeArgumentsCount = 1,
      valueArgumentsCount = 1,
    ).apply {
      dispatchReceiver = irGetInboundCallParameter()
      putTypeArgument(0, resultExpression.type)
      putValueArgument(0, resultExpression)
    }
  }

  /** `inboundCall.unexpectedFunction()` */
  private fun IrBuilderWithScope.irCallUnexpectedFunction(): IrExpression {
    val unexpectedCallFunction = pluginContext.referenceFunctions(
      INBOUND_CALL.child(Name.identifier("unexpectedFunction"))
    ).single()

    return irCall(
      type = pluginContext.symbols.byteArrayType,
      callee = unexpectedCallFunction,
    ).apply {
      dispatchReceiver = irGetInboundCallParameter()
    }
  }

  /** `return ...` */
  private fun IrBuilderWithScope.irReturn(value: IrExpression): IrReturn {
    return IrReturnImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = value.type,
      returnTargetSymbol = function.symbol,
      value = value,
    )
  }

  /** Express [function] as a `Function1<InboundCall<JsEchoService>, ByteArray>`. */
  private fun IrBuilderWithScope.irFunction1Expression(): IrFunctionExpression {
    val function1 = pluginContext.referenceClass(FqName("kotlin.Function1"))!!
    val function1WithTypeParameters = function1.typeWith(
      inboundCallOfTType,
      pluginContext.symbols.byteArrayType,
    )

    return IrFunctionExpressionImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = function1WithTypeParameters,
      function = function,
      origin = IrStatementOrigin.ANONYMOUS_FUNCTION,
    )
  }

  companion object {
    val CREATE_JS_SERVICE = FqName("app.cash.quickjs.ktbridge.createJsService")
    val BRIDGE_TO_JS = FqName("app.cash.quickjs.ktbridge.BridgeToJs")
    val INBOUND_CALL = FqName("app.cash.quickjs.ktbridge.InboundCall")
  }
}