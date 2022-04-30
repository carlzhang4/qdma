package qdma

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, requireIsChiselType}
import common.axi._
import common.storage._


class DataBoundarySplit extends Module{
	val io = IO(new Bundle{
		val data_in = Flipped(Decoupled(new C2H_DATA))
		val cmd_in = Flipped(Decoupled(new C2H_CMD))
		val data_out = Decoupled(new C2H_DATA)
		val cmd_out = Decoupled(new C2H_CMD)
	})

	val data_fifo = XQueue(new C2H_DATA(),512)
	val cmd_temp = Reg(new C2H_CMD())
	val clength = RegInit(0.U(32.W))
	io.data_in	<> data_fifo.io.in

	val sIDLE :: sREAD_DATA :: Nil 	= Enum(2)
	val state                   	= RegInit(sIDLE)

	io.cmd_in.ready 				:= (state === sIDLE) & (io.cmd_out.ready === 1.U)
	data_fifo.io.out.ready 			:= (state === sREAD_DATA) & (io.data_out.ready === 1.U)

	io.data_out.valid 				:= 0.U
	io.data_out.bits				:= 0.U.asTypeOf(io.data_out.bits)
	io.cmd_out.valid 				:= 0.U
	io.cmd_out.bits					:= 0.U.asTypeOf(io.cmd_out.bits)



	switch(state){
		is(sIDLE){
			when(io.cmd_in.fire()){
				cmd_temp				:= io.cmd_in.bits
                state               	:= sREAD_DATA
				io.cmd_out.valid		:= 1.U
				io.cmd_out.bits 		:= io.cmd_in.bits
			}
		}
		is(sREAD_DATA){
			when(data_fifo.io.out.fire()){
                io.data_out.valid 			:= 1.U
				io.data_out.bits 			<> data_fifo.io.out.bits 
				io.data_out.bits.last		:= false.B
				io.data_out.bits.ctrl_len	:= cmd_temp.len
				clength						:= clength + 64.U
				when((clength + 64.U) >= cmd_temp.len){
					io.data_out.bits.last	:= true.B
					state					:= sIDLE
					clength					:= 0.U
				}
			}
		}
	}

}

class CMDBoundaryCheck[T<:HasAddrLen](private val gen:T, page_size:Int, mini_page_size:Int) extends Module{
	val genType = if (compileOptions.declaredTypeMustBeUnbound) {
		requireIsChiselType(gen)
		gen
	} else {
		if (DataMirror.internal.isSynthesizable(gen)) {
			chiselTypeOf(gen)
		}else {
			gen
		}
	}
	val io = IO(new Bundle{
		val in = Flipped(Decoupled(gen))
		val out = Decoupled(gen)
	})
	val page_offset = page_size-1

	val offset_addr = RegInit(0.U(24.W))
	val new_length = RegInit(0.U(24.W))
	val cmd_addr = RegInit(0.U(64.W))
	val cmd_len = RegInit(0.U(32.W))
	val mini_addr = RegInit(0.U(64.W))
	val mini_len = RegInit(0.U(32.W))	
	val cmd_temp = Reg(gen)

	val sIDLE :: sFIRSTCMD :: sSPLIT :: sMINISPLIT :: sLASTSPLIT :: Nil 	= Enum(5)
	val state                   	= RegInit(sIDLE)

	io.in.ready 						:= (state === sIDLE)

	io.out.valid 					:= 0.U
	io.out.bits						:= 0.U.asTypeOf(io.out.bits)

	switch(state){
		is(sIDLE){
			when(io.in.fire()){
				cmd_addr				:= io.in.bits.addr
				cmd_len					:= io.in.bits.len			
				cmd_temp				:= io.in.bits
				offset_addr				:= io.in.bits.addr & page_offset.U
                state               	:= sFIRSTCMD
				new_length				:= page_size.U - (io.in.bits.addr & page_offset.U)
			}
		}
		is(sFIRSTCMD){
			when((offset_addr + cmd_len) > page_size.U){
				mini_addr				:= cmd_addr
				mini_len				:= new_length					
				cmd_addr				:= cmd_addr + new_length
				cmd_len					:= cmd_len - new_length
				state               	:= sMINISPLIT
			}.otherwise{
				mini_addr				:= cmd_addr
				mini_len				:= cmd_len					
				state               	:= sLASTSPLIT
			}
		}
		is(sSPLIT){
			when(cmd_len > page_size.U){
				mini_addr				:= cmd_addr
				mini_len				:= page_size.U					
				cmd_addr				:= cmd_addr + page_size.U
				cmd_len					:= cmd_len - page_size.U
				state               	:= sMINISPLIT
			}.otherwise{
				mini_addr				:= cmd_addr
				mini_len				:= cmd_len					
				state               	:= sLASTSPLIT
			}
		}
		is(sMINISPLIT){
			when(io.out.ready === 1.U){
				when(mini_len > mini_page_size.U){
					mini_addr				:= mini_addr + mini_page_size.U
					mini_len				:= mini_len - mini_page_size.U
					io.out.valid			:= 1.U
					io.out.bits				:= cmd_temp
					io.out.bits.len			:= mini_page_size.U
					io.out.bits.addr		:= mini_addr
				}.otherwise{
					io.out.valid			:= 1.U
					io.out.bits				:= cmd_temp
					io.out.bits.len			:= mini_len
					io.out.bits.addr		:= mini_addr	
					state					:= sSPLIT			
				}
			}
		}
		is(sLASTSPLIT){
			when(io.out.ready === 1.U){
				when(mini_len > mini_page_size.U){
					mini_addr				:= mini_addr + mini_page_size.U
					mini_len				:= mini_len - mini_page_size.U
					io.out.valid			:= 1.U
					io.out.bits				:= cmd_temp
					io.out.bits.len			:= mini_page_size.U
					io.out.bits.addr		:= mini_addr
				}.otherwise{
					io.out.valid			:= 1.U
					io.out.bits				:= cmd_temp
					io.out.bits.len			:= mini_len
					io.out.bits.addr		:= mini_addr	
					state					:= sIDLE			
				}
			}
		}		
	}

}