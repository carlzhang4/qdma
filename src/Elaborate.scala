package qdma
import chisel3._
import chisel3.util._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import firrtl.options.TargetDirAnnotation

object elaborate extends App {
	println("Generating a %s class".format(args(0)))
	val stage	= new chisel3.stage.ChiselStage
	val arr		= Array("-X", "sverilog", "--full-stacktrace")
	val dir 	= TargetDirAnnotation("Verilog")

	args(0) match{
		case "QDMATop" => stage.execute(arr,Seq(ChiselGeneratorAnnotation(() => new QDMATop()),dir))
		case "TestAXI2Reg" => stage.execute(arr,Seq(ChiselGeneratorAnnotation(() => new TestAXI2Reg()),dir))
		case "TestXRam" => stage.execute(arr,Seq(ChiselGeneratorAnnotation(() => new TestXRam()),dir))
		case "TLB" => stage.execute(arr,Seq(ChiselGeneratorAnnotation(() => new TLB()),dir))
		case "DataBoundarySplit" => stage.execute(arr,Seq(ChiselGeneratorAnnotation(() => new DataBoundarySplit()),dir))
		case _ => println("Module match failed!")
	}
}